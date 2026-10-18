package cn.governance;

import java.time.LocalDateTime;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RiskCalculation {
    public record Source(long recordId,String description,String tags,String source,long departmentId,boolean specialReport,LocalDateTime eventTime) {}
    static final org.springframework.jdbc.core.RowMapper<Source> SOURCE_MAPPER = (r,n)->new Source(r.getLong("id"),r.getString("problem_description"),Objects.toString(r.getString("keyword_tags"),""),r.getString("event_source"),r.getLong("department_id"),r.getBoolean("special_report"),r.getObject("occurred_at",LocalDateTime.class));
    public record EffectiveEvent(long id,LocalDateTime eventTime,List<Source> sources) {}
    public record Snapshot(long personId,long effectiveCount,long sourceCount,LocalDateTime lastEventTime,String baseLevel,String finalLevel,boolean specialFloor,String ruleVersion,String ruleLevel,String manualLevel,List<EffectiveEvent> events,List<KeywordRecognition.Hit> keywordHits) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final KeywordRecognition keywords;
    private final AlertLifecycle alerts;
    public RiskCalculation(JdbcTemplate jdbc,ObjectMapper json,KeywordRecognition keywords,AlertLifecycle alerts) { this.jdbc=jdbc;this.json=json;this.keywords=keywords;this.alerts=alerts; }
    public List<EffectiveEvent> evidence(long personId) {
        List<EffectiveEvent> result=new ArrayList<>();
        var ids=jdbc.queryForList("SELECT id FROM tb_effective_event WHERE person_id=? ORDER BY id",Long.class,personId);
        for(long id:ids) {
            var sources=jdbc.query("SELECT r.*,COALESCE(p.special_report,0) AS special_report FROM tb_event_source_link l JOIN tb_input_record r ON r.id=l.record_id LEFT JOIN tb_record_provenance p ON p.record_id=r.id WHERE l.effective_event_id=? AND r.matched_person_id=? AND r.identity_review_state='CONFIRMED' AND r.duplicate_review_state IN ('INDEPENDENT','MERGED') AND r.is_deleted=0 ORDER BY r.id",
                SOURCE_MAPPER,id,personId);
            if(!sources.isEmpty()) {
                // 同一业务事件以最早来源发生时间为事件时间，归集不因晚报时间虚增最近事件时间。
                var time=sources.stream().map(Source::eventTime).min(LocalDateTime::compareTo).orElseThrow();
                result.add(new EffectiveEvent(id,time,sources));
            }
        }
        return result;
    }
    public Snapshot calculate(long personId) {
        return calculate(personId,activeRules(),activeVersion());
    }
    public Snapshot calculate(long personId,Rules config,String version) {
        var events=evidence(personId);long count=events.size();
        String base=count>=config.high()?"HIGH":count>=config.medium()?"MEDIUM":"LOW";
        boolean special=events.stream().flatMap(e->e.sources().stream()).anyMatch(Source::specialReport);
        var hits=keywords.recognize(events,config.keywords());
        String ruleLevel=(special || !hits.isEmpty()) && "LOW".equals(base)?"MEDIUM":base;
        String fingerprint=basis(events,hits,config);
        var adjustments=jdbc.queryForList("SELECT level,fingerprint,active FROM tb_risk_adjustment WHERE person_id=? ORDER BY id DESC LIMIT 1",personId);
        String manual=adjustments.isEmpty() || !Boolean.TRUE.equals(adjustments.getFirst().get("active")) || !fingerprint.equals(adjustments.getFirst().get("fingerprint"))?null:adjustments.getFirst().get("level").toString();
        String finalLevel=manual==null?ruleLevel:manual;
        if(special && "LOW".equals(finalLevel)) finalLevel="MEDIUM";
        return new Snapshot(personId,count,jdbc.queryForObject("SELECT COUNT(*) FROM tb_input_record WHERE matched_person_id=? AND is_deleted=0",Long.class,personId),events.stream().map(EffectiveEvent::eventTime).max(LocalDateTime::compareTo).orElse(null),base,finalLevel,special,version,ruleLevel,manual,events,hits);
    }
    public record Rules(int medium,int high,List<KeywordRecognition.Rule> keywords) {}
    public Rules activeRules() {
        var payload=jdbc.queryForList("SELECT payload FROM tb_rule_release WHERE state='PUBLISHED' ORDER BY id DESC LIMIT 1",String.class);
        if(payload.isEmpty()) return new Rules(3,5,keywords.rules());
        try { return json.readValue(payload.getFirst(),Rules.class); } catch(Exception e) { throw new IllegalStateException(e); }
    }
    public String activeVersion() {
        var ids=jdbc.queryForList("SELECT id FROM tb_rule_release WHERE state='PUBLISHED' ORDER BY id DESC LIMIT 1",Long.class);
        return ids.isEmpty()?"INITIAL-1":"RELEASE-"+ids.getFirst();
    }
    public String basis(List<EffectiveEvent> events,List<KeywordRecognition.Hit> hits,Rules rules) { return keywords.fingerprint(List.of(events,hits,rules)); }
    public void recompute(long personId) {
        keywords.invalidate(personId,evidence(personId),activeRules().keywords());
        var snapshot=calculate(personId);
        jdbc.update("UPDATE tb_risk_adjustment SET active=0 WHERE person_id=? AND fingerprint<>?",personId,basis(snapshot.events(),snapshot.keywordHits(),activeRules()));
        try {
            var before=jdbc.queryForList("SELECT snapshot FROM tb_person_risk WHERE person_id=?",String.class,personId);
            long history=JdbcIds.insert(jdbc,"INSERT INTO tb_risk_history(person_id,snapshot) VALUES (?,?)",personId,json.writeValueAsString(snapshot));
            Snapshot previous=before.isEmpty()?null:json.readValue(before.getFirst(),Snapshot.class);
            String old=previous==null?null:previous.finalLevel();
            alerts.changed(snapshot,previous);
            if(!Objects.equals(old,snapshot.finalLevel())) jdbc.update("INSERT INTO tb_risk_notification(person_id,before_level,after_level,history_id) VALUES (?,?,?,?)",personId,old,snapshot.finalLevel(),history);
            jdbc.update("INSERT INTO tb_person_risk(person_id,snapshot) VALUES (?,?) ON DUPLICATE KEY UPDATE snapshot=VALUES(snapshot)",personId,json.writeValueAsString(snapshot)); }
        catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("风险依据保存失败",e); }
    }
    public Snapshot snapshot(long personId) {
        var rows=jdbc.queryForList("SELECT snapshot FROM tb_person_risk WHERE person_id=?",String.class,personId);
        if(rows.isEmpty()) return calculate(personId);
        try { var saved=json.readValue(rows.getFirst(),Snapshot.class);return Objects.equals(saved.ruleVersion(),activeVersion())?saved:calculate(personId); }
        catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("风险依据读取失败",e); }
    }
}
