package cn.governance;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;

@Service
public class KeywordRecognition {
    public record Rule(long id,String name,List<String> terms,boolean enabled,long version) {}
    public record TextEvidence(long recordId,String field,String text,String term,int start,int end) {}
    public record Hit(Long eventId,long ruleId,String ruleName,long ruleVersion,List<String> terms,List<TextEvidence> evidence) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public KeywordRecognition(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc;this.json=json; }
    public List<Rule> rules() {
        return jdbc.query("SELECT * FROM tb_keyword_rule ORDER BY id",(r,n)->{
            try { return new Rule(r.getLong("id"),r.getString("name"),Arrays.asList(json.readValue(r.getString("terms"),String[].class)),r.getBoolean("enabled"),r.getLong("version")); }
            catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
        });
    }
    public List<Hit> recognize(List<RiskCalculation.EffectiveEvent> events) {
        var payload=jdbc.queryForList("SELECT payload FROM tb_rule_release WHERE state='PUBLISHED' ORDER BY id DESC LIMIT 1",String.class);
        try { return recognize(events,payload.isEmpty()?rules():json.readValue(payload.getFirst(),RiskCalculation.Rules.class).keywords()); } catch(Exception e) { throw new IllegalStateException(e); }
    }
    public List<Hit> recognize(List<RiskCalculation.EffectiveEvent> events,List<Rule> rules) {
        return recognize(events,rules,false);
    }
    private List<Hit> recognize(List<RiskCalculation.EffectiveEvent> events,List<Rule> rules,boolean raw) {
        List<Hit> hits=new ArrayList<>();
        for(var event:events) for(var rule:rules) {
            if(!rule.enabled()) continue;
            List<TextEvidence> evidence=new ArrayList<>();
            boolean complete=true;
            for(String term:rule.terms()) {
                List<TextEvidence> found=new ArrayList<>();
                for(var source:event.sources()) {
                    for(var field:Map.of("description",source.description(),"tags",source.tags()).entrySet()) {
                        int index=field.getValue().indexOf(term);
                        if(index>=0) found.add(new TextEvidence(source.recordId(),field.getKey(),field.getValue(),term,index,index+term.length()));
                    }
                }
                if(found.isEmpty()) { complete=false;break; }
                evidence.addAll(found);
            }
            evidence.sort(Comparator.comparingLong(TextEvidence::recordId).thenComparing(TextEvidence::field).thenComparing(TextEvidence::term));
            if(complete) {
                var hit=new Hit(event.id(),rule.id(),rule.name(),rule.version(),rule.terms(),evidence);
                if(raw || jdbc.queryForObject("SELECT COUNT(*) FROM tb_keyword_exclusion WHERE active=1 AND event_id=? AND rule_id=? AND fingerprint=?",Long.class,event.id(),rule.id(),fingerprint(hit))==0) hits.add(hit);
            }
        }
        return hits;
    }
    public void invalidate(long personId,List<RiskCalculation.EffectiveEvent> events,List<Rule> rules) {
        var hits=recognize(events,rules,true);
        for(var row:jdbc.queryForList("SELECT x.* FROM tb_keyword_exclusion x JOIN tb_effective_event e ON e.id=x.event_id WHERE e.person_id=? AND x.active=1",personId)) {
            boolean valid=hits.stream().anyMatch(h->h.eventId().equals(((Number)row.get("event_id")).longValue())&&h.ruleId()==((Number)row.get("rule_id")).longValue()&&fingerprint(h).equals(row.get("fingerprint")));
            if(!valid) jdbc.update("UPDATE tb_keyword_exclusion SET active=0 WHERE id=?",row.get("id"));
        }
    }
    public String fingerprint(Object value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(value))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    public List<Hit> record(long id) {
        var sources=jdbc.query("SELECT r.*,COALESCE(p.special_report,0) AS special_report FROM tb_input_record r LEFT JOIN tb_record_provenance p ON p.record_id=r.id WHERE r.id=?",RiskCalculation.SOURCE_MAPPER,id);
        Long eventId=jdbc.queryForList("SELECT effective_event_id FROM tb_event_source_link WHERE record_id=?",Long.class,id).stream().findFirst().orElse(null);
        return recognize(List.of(new RiskCalculation.EffectiveEvent(eventId==null?0:eventId,sources.getFirst().eventTime(),sources))).stream()
            .map(h->new Hit(eventId,h.ruleId(),h.ruleName(),h.ruleVersion(),h.terms(),h.evidence())).toList();
    }
}
