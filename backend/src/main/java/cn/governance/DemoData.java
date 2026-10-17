package cn.governance;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DemoData implements CommandLineRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final String password;

    public DemoData(JdbcTemplate jdbc, PasswordEncoder encoder, @Value("${app.demo-password}") String password) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (password.isBlank() || jdbc.queryForObject("SELECT COUNT(*) FROM tb_user", Long.class) != 0) return;
        if (password.length() < 12) throw new IllegalArgumentException("演示密码至少 12 位");
        jdbc.update("INSERT INTO tb_department (id, name, department_type) VALUES (1, '县综治中心', 'CENTER'), (2, '民政局', 'DEPARTMENT'), (3, '公安局', 'DEPARTMENT')");
        seedUser(1, "center", "中心研判员", 1, "CENTER_ANALYST", true);
        seedUser(2, "civil", "民政上报员", 2, "DEPARTMENT_REPORTER", true);
        seedUser(3, "police", "公安处置员", 3, "DEPARTMENT_HANDLER", true);
        seedUser(4, "admin", "中心管理员", 1, "CENTER_ADMIN", true);
        seedUser(5, "disabled", "已停用账号", 2, "DEPARTMENT_REPORTER", false);
        jdbc.update("""
                INSERT INTO tb_input_record
                  (id, party_name, occurred_at, event_category, dispute_type, problem_description, event_source, department_id, created_by, modified_by)
                VALUES
                  (1, '演示张某', '2026-10-15 09:30:00', '民生诉求', '救助咨询', '虚构案例：咨询临时救助申请材料。', '部门上报', 2, 'civil', 'civil'),
                  (2, '演示李某', '2026-10-16 14:20:00', '矛盾纠纷', '邻里纠纷', '虚构案例：邻里停车位使用产生争议，申请协调。', '部门上报', 3, 'police', 'police'),
                  (3, '演示王某', '2026-10-17 10:00:00', '民生诉求', '养老服务', '虚构案例：咨询社区养老服务办理流程。', '部门上报', 2, 'civil', 'civil')
                """);
    }

    private void seedUser(long id, String username, String name, long department, String role, boolean enabled) {
        jdbc.update("INSERT INTO tb_user (id, username, display_name, password_hash, department_id, enabled) VALUES (?, ?, ?, ?, ?, ?)",
                id, username, name, encoder.encode(password), department, enabled);
        jdbc.update("INSERT INTO tb_user_role (user_id, role_code) VALUES (?, ?)", id, role);
    }
}
