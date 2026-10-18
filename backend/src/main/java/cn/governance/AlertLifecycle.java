package cn.governance;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Called inside the same locked transaction that persists a risk calculation. */
@Service
public class AlertLifecycle {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;
    private final KeywordRecognition keywords;
    public AlertLifecycle(JdbcTemplate jdbc,ObjectMapper json,Clock clock,KeywordRecognition keywords) {this.jdbc=jdbc;this.json=json;this.clock=clock;this.keywords=keywords;}
    private int rank(String s) {return "HIGH".equals(s)?2:"MEDIUM".equals(s)?1:0;}
    private RiskCalculation.Snapshot decode(String s) {try{return json.readValue(s,RiskCalculation.Snapshot.class);}catch(Exception e){throw new IllegalStateException(e);}}
    public void changed(RiskCalculation.Snapshot current,RiskCalculation.Snapshot previous) {
        String data;
        try{data=json.writeValueAsString(current);}catch(Exception e){throw new IllegalStateException(e);}
        // Exclude contact fields and rules' version-only changes; keep effective evidence and final outcome.
        var hits=current.keywordHits().stream().map(h->List.of(h.eventId(),h.ruleId(),h.ruleName(),h.terms(),h.evidence())).toList();
        String hash=keywords.fingerprint(List.of(current.events(),hits,current.finalLevel()));
        var open=jdbc.queryForList("SELECT * FROM tb_alert WHERE person_id=? AND state='OPEN' ORDER BY id DESC LIMIT 1",current.personId());
        var now=LocalDateTime.now(clock);
        if(!open.isEmpty()) {
            var a=open.getFirst();
            if(!hash.equals(a.get("basis_hash"))) jdbc.update("UPDATE tb_alert SET level=?,snapshot=?,basis_hash=?,new_basis=1,updated_at=?,version=version+1 WHERE id=?",current.finalLevel(),data,hash,now,a.get("id"));
            else jdbc.update("UPDATE tb_alert SET snapshot=? WHERE id=?",data,a.get("id"));
            return;
        }
        var latest=jdbc.queryForList("SELECT snapshot FROM tb_alert WHERE person_id=? ORDER BY id DESC LIMIT 1",String.class,current.personId());
        if(latest.isEmpty() && rank(current.finalLevel())==0) return;
        boolean trigger=latest.isEmpty();
        if(!trigger) {
            var closed=decode(latest.getFirst());
            Set<Long> oldIds=new HashSet<>(closed.events().stream().map(RiskCalculation.EffectiveEvent::id).toList());
            trigger=rank(current.finalLevel())>rank(previous==null?closed.finalLevel():previous.finalLevel()) || current.events().stream().anyMatch(e->!oldIds.contains(e.id()));
        }
        if(trigger) JdbcIds.insert(jdbc,"INSERT INTO tb_alert(person_id,level,snapshot,basis_hash,created_at,updated_at) VALUES (?,?,?,?,?,?)",current.personId(),current.finalLevel(),data,hash,now,now);
    }
}
