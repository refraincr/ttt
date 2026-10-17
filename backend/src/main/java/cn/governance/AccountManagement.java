package cn.governance;

import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountManagement {
    public record Department(long id, String name, String departmentType, boolean enabled, long version) {}
    public record DepartmentWrite(String name, String departmentType, Boolean enabled, Long version) {}
    public record UserView(long id, String username, String displayName, long departmentId, String departmentName,
                           boolean enabled, List<Role> roles, long version) {}
    public record UserWrite(String username, String displayName, Long departmentId, Boolean enabled,
                            List<Role> roles, String password, Long version) {}

    private final JdbcTemplate jdbc;
    private final AccountService accounts;
    private final PasswordEncoder encoder;
    private final ManagementAudit audit;
    public AccountManagement(JdbcTemplate jdbc, AccountService accounts, PasswordEncoder encoder, ManagementAudit audit) {
        this.jdbc = jdbc; this.accounts = accounts; this.encoder = encoder; this.audit = audit;
    }

    public List<Department> departments(Authentication auth) { accounts.administrator(auth); return allDepartments(); }
    private List<Department> allDepartments() {
        return jdbc.query("SELECT id, name, department_type, enabled, version FROM tb_department ORDER BY id",
                (rs, row) -> new Department(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getLong(5)));
    }
    private Department department(long id) {
        return allDepartments().stream().filter(item -> item.id() == id).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "部门不存在"));
    }
    public List<UserView> users(Authentication auth) { accounts.administrator(auth); return allUsers(); }
    private List<UserView> allUsers() {
        return jdbc.query("""
                SELECT u.id, u.username, u.display_name, u.department_id, d.name, u.enabled, u.version
                FROM tb_user u JOIN tb_department d ON d.id = u.department_id ORDER BY u.id
                """, (rs, row) -> new UserView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                        rs.getString(5), rs.getBoolean(6), userRoles(rs.getLong(1)), rs.getLong(7)));
    }
    private List<Role> userRoles(long id) {
        return jdbc.queryForList("SELECT role_code FROM tb_user_role WHERE user_id = ?", String.class, id)
                .stream().map(Role::valueOf).sorted(Comparator.comparingInt(Enum::ordinal)).toList();
    }
    private UserView user(long id) {
        return allUsers().stream().filter(item -> item.id() == id).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "账号不存在"));
    }

    private void lockConfiguration() { jdbc.queryForObject("SELECT id FROM tb_configuration_lock WHERE id = 1 FOR UPDATE", Long.class); }
    private void retainAdministrator() {
        long remaining = jdbc.queryForObject("""
                SELECT COUNT(*) FROM tb_user u JOIN tb_department d ON d.id = u.department_id
                JOIN tb_user_role r ON r.user_id = u.id AND r.role_code = 'CENTER_ADMIN'
                WHERE u.enabled = 1 AND d.enabled = 1 AND d.department_type = 'CENTER'
                """, Long.class);
        if (remaining == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "必须保留至少一个可用的中心管理员");
    }

    @Transactional
    public Department saveDepartment(Authentication auth, Long id, DepartmentWrite input) {
        lockConfiguration();
        var actor = accounts.administrator(auth);
        String name = InputChecks.text(input.name(), 100, "部门名称");
        InputChecks.require(List.of("CENTER", "DEPARTMENT").contains(input.departmentType() == null ? "" : input.departmentType()), "部门类型无效");
        InputChecks.require(input.enabled() != null, "请明确启用状态");
        Department before = id == null ? null : department(id);
        if (before == null) {
            id = JdbcIds.insert(jdbc, "INSERT INTO tb_department (name, department_type, enabled, created_by, modified_by) VALUES (?, ?, ?, ?, ?)",
                    name, input.departmentType(), input.enabled(), actor.id(), actor.id());
        } else {
            InputChecks.version(input.version(), before.version());
            InputChecks.require(before.departmentType().equals(input.departmentType()), "部门类型建立后不能变更");
            jdbc.update("UPDATE tb_department SET name = ?, enabled = ?, version = version + 1, modified_by = ? WHERE id = ?",
                    name, input.enabled(), actor.id(), id);
            if (input.enabled() != before.enabled()) {
                jdbc.update("UPDATE tb_user SET session_version = session_version + 1 WHERE department_id = ?", id);
            }
        }
        retainAdministrator();
        Department after = department(id);
        audit.record(actor, "DEPARTMENT", id, before, after);
        return after;
    }

    @Transactional
    public UserView saveUser(Authentication auth, Long id, UserWrite input) {
        lockConfiguration();
        var actor = accounts.administrator(auth);
        String name = InputChecks.text(input.displayName(), 100, "姓名");
        InputChecks.require(input.departmentId() != null, "账号必须归属部门");
        Department department = department(input.departmentId());
        InputChecks.require(input.enabled() != null, "请明确启用状态");
        InputChecks.require(input.roles() != null && !input.roles().isEmpty() && !input.roles().contains(null), "至少选择一个有效角色");
        List<Role> roles = input.roles().stream().distinct().sorted(Comparator.comparingInt(Enum::ordinal)).toList();
        InputChecks.require(roles.stream().allMatch(role -> role.isCenterRole() == department.departmentType().equals("CENTER")), "中心角色与部门角色须分别归属对应类型单位");
        InputChecks.require(!roles.contains(Role.SPECIAL_REPORTER) || roles.contains(Role.DEPARTMENT_REPORTER), "特殊上报是附加权限，须同时具备部门上报权限");
        UserView before = id == null ? null : user(id);
        if (before == null) {
            String username = InputChecks.text(input.username(), 50, "账号");
            InputChecks.require(username.matches("[A-Za-z0-9_.-]{3,50}"), "账号须为3至50位字母、数字、下划线、点或短横线");
            InputChecks.password(input.password());
            id = JdbcIds.insert(jdbc, """
                    INSERT INTO tb_user (username, display_name, department_id, enabled, password_hash, created_by, modified_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, username, name, department.id(), input.enabled(), encoder.encode(input.password()), actor.id(), actor.id());
        } else {
            InputChecks.version(input.version(), before.version());
            InputChecks.require(input.username() == null || input.username().equals(before.username()), "登录账号建立后不能更名");
            jdbc.update("""
                    UPDATE tb_user SET display_name = ?, department_id = ?, enabled = ?, version = version + 1,
                      session_version = session_version + 1, modified_by = ? WHERE id = ?
                    """, name, department.id(), input.enabled(), actor.id(), id);
            if (input.password() != null && !input.password().isEmpty()) {
                InputChecks.password(input.password());
                jdbc.update("UPDATE tb_user SET password_hash = ? WHERE id = ?", encoder.encode(input.password()), id);
            }
            jdbc.update("DELETE FROM tb_user_role WHERE user_id = ?", id);
        }
        for (Role role : roles) jdbc.update("INSERT INTO tb_user_role (user_id, role_code) VALUES (?, ?)", id, role.name());
        retainAdministrator();
        UserView after = user(id);
        audit.record(actor, "USER", id, before, after);
        return after;
    }
}
