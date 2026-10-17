package cn.governance;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountService implements UserDetailsService {
    private final JdbcTemplate jdbc;

    public AccountService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Account(long id, String username, String displayName, long departmentId,
                          String departmentName, List<String> roles) {
        public boolean canReadAll() { return roles.contains("CENTER_ANALYST"); }
        public boolean canReadDepartment() {
            return roles.contains("DEPARTMENT_REPORTER") || roles.contains("DEPARTMENT_HANDLER");
        }
    }

    public Account current(Authentication authentication) {
        return jdbc.query("""
                SELECT u.id, u.username, u.display_name, u.department_id, d.name
                FROM tb_user u JOIN tb_department d ON d.id = u.department_id
                WHERE u.username = ? AND u.enabled = 1 AND d.enabled = 1
                """, (rs, row) -> new Account(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getLong(4), rs.getString(5), roles(rs.getLong(1))), authentication.getName())
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号不可用"));
    }

    private List<String> roles(long id) {
        return jdbc.queryForList("SELECT role_code FROM tb_user_role WHERE user_id = ?", String.class, id);
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return jdbc.query("""
                SELECT u.id, u.username, u.password_hash, u.enabled, d.enabled AS department_enabled
                FROM tb_user u JOIN tb_department d ON d.id = u.department_id WHERE u.username = ?
                """, (rs, row) -> User.withUsername(rs.getString("username"))
                        .password(rs.getString("password_hash"))
                        .disabled(!rs.getBoolean("enabled") || !rs.getBoolean("department_enabled"))
                        .authorities(roles(rs.getLong("id")).toArray(String[]::new)).build(), username)
                .stream().findFirst().orElseThrow(() -> new UsernameNotFoundException("账号或密码错误"));
    }
}
