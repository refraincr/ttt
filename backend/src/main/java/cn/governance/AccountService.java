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
            return roles.contains("DEPARTMENT_REPORTER");
        }
    }

    public static class SessionUser extends User {
        private final long accountId;
        private final long sessionVersion;
        public SessionUser(long id, long version, UserDetails details) {
            super(details.getUsername(), details.getPassword(), details.isEnabled(), true, true, true, details.getAuthorities());
            this.accountId = id;
            this.sessionVersion = version;
        }
    }

    static class TrustedIntakeToken extends org.springframework.security.authentication.AbstractAuthenticationToken {
        final Account account;
        TrustedIntakeToken(Account account){super(List.of());this.account=account;}
        public Object getPrincipal(){return account.username();}
        public Object getCredentials(){return "";}
    }
    public Account current(Authentication authentication) {
        if(authentication instanceof TrustedIntakeToken trusted) return trusted.account;
        var found=jdbc.query("""
                SELECT u.id, u.username, u.display_name, u.department_id, d.name
                FROM tb_user u JOIN tb_department d ON d.id = u.department_id
                WHERE u.id = ? AND u.session_version = ? AND u.enabled = 1 AND d.enabled = 1
                """, (rs, row) -> new Account(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getLong(4), rs.getString(5), List.of()),
                        ((SessionUser) authentication.getPrincipal()).accountId,
                        ((SessionUser) authentication.getPrincipal()).sessionVersion)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号不可用"));
        return new Account(found.id(),found.username(),found.displayName(),found.departmentId(),found.departmentName(),roles(found.id()));
    }

    private List<String> roles(long id) {
        return jdbc.queryForList("SELECT role_code FROM tb_user_role WHERE user_id = ? ORDER BY FIELD(role_code, 'CENTER_ADMIN', 'CENTER_ANALYST', 'DEPARTMENT_REPORTER', 'DEPARTMENT_HANDLER', 'SPECIAL_REPORTER')", String.class, id);
    }

    public Account administrator(Authentication authentication) {
        Account account = current(authentication);
        if (!account.roles().contains("CENTER_ADMIN")) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要中心管理权限");
        return account;
    }

    private record Login(long id,long version,String username,String password,boolean enabled) {}
    @Override
    public UserDetails loadUserByUsername(String username) {
        var login=jdbc.query("SELECT u.id,u.session_version,u.username,u.password_hash,u.enabled AND d.enabled AS active FROM tb_user u JOIN tb_department d ON d.id=u.department_id WHERE u.username=?",(rs,n)->new Login(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getString(4),rs.getBoolean(5)),username).stream().findFirst().orElseThrow(()->new UsernameNotFoundException("账号或密码错误"));
        return new SessionUser(login.id(),login.version(),User.withUsername(login.username()).password(login.password()).disabled(!login.enabled()).authorities(roles(login.id()).toArray(String[]::new)).build());
    }
}
