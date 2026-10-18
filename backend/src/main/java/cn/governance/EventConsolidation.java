package cn.governance;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Maintains one effective event for each confirmed business occurrence. Caller holds intake transaction lock. */
@Service
public class EventConsolidation {
    private final JdbcTemplate jdbc;
    public EventConsolidation(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Long eventId(long recordId) {
        return jdbc.queryForList("SELECT effective_event_id FROM tb_event_source_link WHERE record_id=?",Long.class,recordId).stream().findFirst().orElse(null);
    }
    public long independent(long recordId,String actor,String reason) {
        Long existing=eventId(recordId);
        if(existing!=null) return existing;
        long person=jdbc.queryForObject("SELECT matched_person_id FROM tb_input_record WHERE id=? AND identity_review_state='CONFIRMED' AND is_deleted=0",Long.class,recordId);
        long event=JdbcIds.insert(jdbc,"INSERT INTO tb_effective_event(person_id) VALUES (?)",person);
        attach(recordId,event,"INDEPENDENT",actor,reason);
        return event;
    }
    public void attach(long recordId,long event,String decision,String actor,String reason) {
        jdbc.update("INSERT INTO tb_event_source_link(record_id,effective_event_id) VALUES (?,?)",recordId,event);
        jdbc.update("UPDATE tb_input_record SET duplicate_review_state=?,review_status=1,version=version+1 WHERE id=?",decision,recordId);
        jdbc.update("INSERT INTO tb_duplicate_decision(record_id,effective_event_id,decision,reason,actor_username) VALUES (?,?,?,?,?)",recordId,event,decision,reason,actor);
    }
    public List<Map<String,Object>> history(long recordId) {
        return jdbc.queryForList("SELECT * FROM tb_duplicate_decision WHERE record_id=? ORDER BY id",recordId);
    }
    public long count(long personId) {
        return jdbc.queryForObject("SELECT COUNT(DISTINCT e.id) FROM tb_effective_event e JOIN tb_event_source_link l ON l.effective_event_id=e.id JOIN tb_input_record r ON r.id=l.record_id WHERE e.person_id=? AND r.matched_person_id=e.person_id AND r.identity_review_state='CONFIRMED' AND r.duplicate_review_state IN ('INDEPENDENT','MERGED') AND r.is_deleted=0",Long.class,personId);
    }
}
