package cn.governance;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(1)
public class DemoAccountExtensions implements CommandLineRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final String password;
    public DemoAccountExtensions(JdbcTemplate jdbc, PasswordEncoder encoder, @Value("${app.demo-password}") String password) {
        this.jdbc = jdbc; this.encoder = encoder; this.password = password;
    }
    @Override
    @Transactional
    public void run(String... args) {
        if (password.isBlank()) return;
        jdbc.queryForObject("SELECT id FROM tb_configuration_lock WHERE id = 1 FOR UPDATE", Long.class);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM tb_demo_initialization WHERE seed_name = 'accounts-v2'", Long.class) > 0) return;
        InputChecks.password(password);
        var civil = jdbc.queryForList("SELECT id FROM tb_department WHERE name = '民政局' AND department_type = 'DEPARTMENT'", Long.class);
        if (civil.isEmpty()) return;
        var towns = jdbc.queryForList("SELECT id FROM tb_department WHERE name = '示例乡镇'", Long.class);
        long town = towns.isEmpty() ? JdbcIds.insert(jdbc, "INSERT INTO tb_department (name, department_type) VALUES ('示例乡镇', 'DEPARTMENT')") : towns.getFirst();
        seed("special", "演示特殊上报员", civil.getFirst(), List.of(Role.DEPARTMENT_REPORTER, Role.DEPARTMENT_HANDLER, Role.SPECIAL_REPORTER));
        seed("town", "示例乡镇上报员", town, List.of(Role.DEPARTMENT_REPORTER));
        jdbc.update("INSERT INTO tb_demo_initialization (seed_name) VALUES ('accounts-v2')");
    }
    private void seed(String username, String name, long department, List<Role> roles) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM tb_user WHERE username = ?", Long.class, username) > 0) return;
        long id = JdbcIds.insert(jdbc, "INSERT INTO tb_user (username, display_name, password_hash, department_id) VALUES (?, ?, ?, ?)", username, name, encoder.encode(password), department);
        for (Role role : roles) jdbc.update("INSERT INTO tb_user_role VALUES (?, ?)", id, role.name());
    }
}
