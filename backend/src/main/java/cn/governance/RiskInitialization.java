package cn.governance;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Upgrades pre-consolidation records without changing original provenance. Idempotent on restart. */
@Component
public class RiskInitialization implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final DuplicateScreening duplicates;
    private final RiskCalculation risk;
    public RiskInitialization(JdbcTemplate jdbc,DuplicateScreening duplicates,RiskCalculation risk) { this.jdbc=jdbc;this.duplicates=duplicates;this.risk=risk; }
    @Override @Transactional
    public void run(ApplicationArguments args) {
        jdbc.queryForObject("SELECT id FROM tb_intake_lock WHERE id=1 FOR UPDATE",Long.class);
        jdbc.queryForList("SELECT id FROM tb_input_record WHERE identity_review_state='CONFIRMED' AND is_deleted=0 ORDER BY id",Long.class).forEach(duplicates::screen);
        jdbc.queryForList("SELECT id FROM tb_person ORDER BY id",Long.class).forEach(risk::recompute);
    }
}
