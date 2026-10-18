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
    public record Snapshot(long personId,long effectiveCount,long sourceCount,LocalDateTime lastEventTime,String baseLevel,String finalLevel,boolean specialFloor,String ruleVersion,List<EffectiveEvent> events,List<KeywordRecognition.Hit> keywordHits) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final KeywordRecognition keywords;
    public RiskCalculation(JdbcTemplate jdbc,ObjectMapper json,KeywordRecognition keywords) { this.jdbc=jdbc;this.json=json;this.keywords=keywords; }
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
        var events=evidence(personId);long count=events.size();
        String base=count>=5?"HIGH":count>=3?"MEDIUM":"LOW";
        boolean special=events.stream().flatMap(e->e.sources().stream()).anyMatch(Source::specialReport);
        var hits=keywords.recognize(events);
        return new Snapshot(personId,count,jdbc.queryForObject("SELECT COUNT(*) FROM tb_input_record WHERE matched_person_id=? AND is_deleted=0",Long.class,personId),events.stream().map(EffectiveEvent::eventTime).max(LocalDateTime::compareTo).orElse(null),base,(special || !hits.isEmpty()) && "LOW".equals(base)?"MEDIUM":base,special,"INITIAL-1",events,hits);
    }
    public void recompute(long personId) {
        var snapshot=calculate(personId);
        try { jdbc.update("INSERT INTO tb_person_risk(person_id,snapshot) VALUES (?,?) ON DUPLICATE KEY UPDATE snapshot=VALUES(snapshot)",personId,json.writeValueAsString(snapshot)); }
        catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("风险依据保存失败",e); }
    }
    public Snapshot snapshot(long personId) {
        var rows=jdbc.queryForList("SELECT snapshot FROM tb_person_risk WHERE person_id=?",String.class,personId);
        if(rows.isEmpty()) return calculate(personId);
        try { return json.readValue(rows.getFirst(),Snapshot.class); }
        catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("风险依据读取失败",e); }
    }
}
