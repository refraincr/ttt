package cn.governance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuthenticationAudit {
    private final JdbcTemplate jdbc;
    public AuthenticationAudit(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void record(String username, String action, String result) {
        String actor = username == null ? "" : username.substring(0, Math.min(50, username.length()));
        jdbc.update("INSERT INTO tb_operation_log (actor_username, action, result) VALUES (?, ?, ?)", actor, action, result);
    }
}
