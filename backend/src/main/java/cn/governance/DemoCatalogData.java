package cn.governance;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(2)
public class DemoCatalogData implements CommandLineRunner {
    private final JdbcTemplate jdbc;
    private final String password;
    public DemoCatalogData(JdbcTemplate jdbc, @Value("${app.demo-password}") String password) { this.jdbc = jdbc; this.password = password; }
    @Override
    @Transactional
    public void run(String... args) {
        if (password.isBlank()) return;
        jdbc.queryForObject("SELECT id FROM tb_configuration_lock WHERE id = 1 FOR UPDATE", Long.class);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM tb_demo_initialization WHERE seed_name = 'catalog-v1'", Long.class) > 0) return;
        if (jdbc.queryForObject("SELECT COUNT(*) FROM tb_data_source WHERE name = '部门上报'", Long.class) == 0) {
            jdbc.update("INSERT INTO tb_data_source (name, source_type, created_by, modified_by) VALUES ('部门上报', 'DEPARTMENT', 'system', 'system')");
        }
        item("EVENT_CATEGORY", "LIVELIHOOD", "民生诉求");
        item("EVENT_CATEGORY", "DISPUTE", "矛盾纠纷");
        item("DISPUTE_TYPE", "ASSISTANCE", "救助咨询");
        item("DISPUTE_TYPE", "NEIGHBOR", "邻里纠纷");
        item("DISPUTE_TYPE", "ELDERCARE", "养老服务");
        jdbc.update("INSERT INTO tb_demo_initialization (seed_name) VALUES ('catalog-v1')");
    }
    private void item(String kind, String code, String name) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM tb_dictionary_item WHERE kind = ? AND (code = ? OR name = ?)", Long.class, kind, code, name) == 0) {
            jdbc.update("INSERT INTO tb_dictionary_item (kind, code, name, created_by, modified_by) VALUES (?, ?, ?, 'system', 'system')", kind, code, name);
        }
    }
}
