package cn.governance;
import java.util.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
/** Repair only missing persisted work; committed business changes already update risk atomically. */
@Component
public class RiskInitialization implements ApplicationRunner {
 private final JdbcTemplate jdbc;private final DuplicateScreening duplicates;private final RiskCalculation risk;
 public RiskInitialization(JdbcTemplate jdbc,DuplicateScreening duplicates,RiskCalculation risk){this.jdbc=jdbc;this.duplicates=duplicates;this.risk=risk;}
 @Override @Transactional public void run(ApplicationArguments args){
  jdbc.queryForObject("SELECT id FROM tb_intake_lock WHERE id=1 FOR UPDATE",Long.class);
  Set<Long> changed=new LinkedHashSet<>();
  for(var record:jdbc.queryForList("SELECT r.id,r.matched_person_id FROM tb_input_record r LEFT JOIN tb_event_source_link l ON l.record_id=r.id WHERE r.identity_review_state='CONFIRMED' AND r.is_deleted=0 AND l.record_id IS NULL ORDER BY r.id")){
   duplicates.screen(((Number)record.get("id")).longValue());changed.add(((Number)record.get("matched_person_id")).longValue());
  }
  changed.addAll(jdbc.queryForList("SELECT p.id FROM tb_person p LEFT JOIN tb_person_risk r ON r.person_id=p.id WHERE r.person_id IS NULL OR JSON_UNQUOTE(JSON_EXTRACT(r.snapshot,'$.ruleVersion'))<>?",Long.class,risk.activeVersion()));
  changed.forEach(risk::recompute);risk.initializeAlerts();
 }
}
