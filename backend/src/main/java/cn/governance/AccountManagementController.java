package cn.governance;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class AccountManagementController {
    private final AccountManagement management;
    private final AccountService accounts;
    public AccountManagementController(AccountManagement management, AccountService accounts) { this.management = management; this.accounts = accounts; }

    @GetMapping("/roles")
    public List<Map<String, String>> roles(Authentication auth) {
        accounts.administrator(auth);
        return Arrays.stream(Role.values()).map(role -> Map.of("code", role.name(), "name", role.label())).toList();
    }
    @GetMapping("/departments")
    public List<AccountManagement.Department> departments(Authentication auth) { return management.departments(auth); }
    @PostMapping("/departments") @ResponseStatus(HttpStatus.CREATED)
    public AccountManagement.Department createDepartment(Authentication auth, @RequestBody AccountManagement.DepartmentWrite input) { return management.saveDepartment(auth, null, input); }
    @PutMapping("/departments/{id}")
    public AccountManagement.Department updateDepartment(Authentication auth, @PathVariable long id, @RequestBody AccountManagement.DepartmentWrite input) { return management.saveDepartment(auth, id, input); }
    @GetMapping("/users")
    public List<AccountManagement.UserView> users(Authentication auth) { return management.users(auth); }
    @PostMapping("/users") @ResponseStatus(HttpStatus.CREATED)
    public AccountManagement.UserView createUser(Authentication auth, @RequestBody AccountManagement.UserWrite input) { return management.saveUser(auth, null, input); }
    @PutMapping("/users/{id}")
    public AccountManagement.UserView updateUser(Authentication auth, @PathVariable long id, @RequestBody AccountManagement.UserWrite input) { return management.saveUser(auth, id, input); }
}
