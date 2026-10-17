package cn.governance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ManagementAudit {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public ManagementAudit(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public void record(AccountService.Account actor, String type, long id, Object before, Object after) {
        try {
            jdbc.update("""
                    INSERT INTO tb_operation_log (actor_username, action, result, target_type, target_id, before_data, after_data)
                    VALUES (?, ?, 'SUCCESS', ?, ?, ?, ?)
                    """, actor.username(), before == null ? "CREATE" : "UPDATE", type, id,
                    before == null ? null : json.writeValueAsString(before), json.writeValueAsString(after));
        } catch (JsonProcessingException error) { throw new IllegalStateException("无法记录操作审计", error); }
    }
}
