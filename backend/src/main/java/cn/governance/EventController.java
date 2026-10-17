package cn.governance;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class EventController {
    private final JdbcTemplate jdbc;
    private final AccountService accounts;
    private static final String SELECT = """
            SELECT r.id, r.party_name, r.occurred_at, r.event_category, r.dispute_type,
                   r.problem_description, r.event_source, d.name AS department_name, r.is_completed
            FROM tb_input_record r JOIN tb_department d ON d.id = r.department_id
            """;

    public EventController(JdbcTemplate jdbc, AccountService accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    public record EventView(long id, String personName, LocalDateTime eventTime, String eventCategory,
                            String disputeType, String eventDescription, String eventSource,
                            String departmentName, boolean isCompleted) {}
    public record EventPage(List<EventView> items, long total) {}
    private static final RowMapper<EventView> EVENT_MAPPER = (rs, row) -> new EventView(
            rs.getLong("id"), rs.getString("party_name"), rs.getObject("occurred_at", LocalDateTime.class),
            rs.getString("event_category"), rs.getString("dispute_type"), rs.getString("problem_description"),
            rs.getString("event_source"), rs.getString("department_name"), rs.getBoolean("is_completed"));

    private String scope(AccountService.Account account, List<Object> parameters) {
        if (account.canReadAll()) return "r.is_deleted = 0";
        if (!account.canReadDepartment()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        parameters.add(account.departmentId());
        return "r.is_deleted = 0 AND r.department_id = ?";
    }

    @GetMapping("/api/events")
    public EventPage list(Authentication authentication,
                          @RequestParam(defaultValue = "1") int page,
                          @RequestParam(defaultValue = "10") int size,
                          @RequestParam(defaultValue = "") String q) {
        if (page < 1 || page > 1000000 || size < 1 || size > 100 || q.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分页或搜索参数无效");
        }
        var parameters = new ArrayList<Object>();
        String where = scope(accounts.current(authentication), parameters);
        if (!q.isBlank()) {
            // 使用 LOCATE 将通配符视为普通文本，查询值始终绑定参数。
            where += " AND (LOCATE(?, r.party_name) > 0 OR LOCATE(?, r.problem_description) > 0)";
            parameters.add(q.trim());
            parameters.add(q.trim());
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM tb_input_record r WHERE " + where,
                Long.class, parameters.toArray());
        parameters.add(size);
        parameters.add((page - 1) * size);
        var items = jdbc.query(SELECT + " WHERE " + where + " ORDER BY r.occurred_at DESC, r.id DESC LIMIT ? OFFSET ?",
                EVENT_MAPPER, parameters.toArray());
        return new EventPage(items, total);
    }

    @GetMapping("/api/events/{id}")
    public EventView detail(Authentication authentication, @PathVariable long id) {
        var parameters = new ArrayList<Object>();
        String where = scope(accounts.current(authentication), parameters);
        parameters.add(id);
        return jdbc.query(SELECT + " WHERE " + where + " AND r.id = ?", EVENT_MAPPER, parameters.toArray())
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
