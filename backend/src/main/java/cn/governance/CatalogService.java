package cn.governance;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CatalogService {
    public enum SourceType { DEPARTMENT, PETITION, EXTERNAL, OTHER }
    public enum DictionaryKind { EVENT_CATEGORY, DISPUTE_TYPE }
    public record Source(long id, String name, SourceType sourceType, Long departmentId, boolean enabled, long version, String departmentName) {}
    public record SourceWrite(String name, SourceType sourceType, Long departmentId, Boolean enabled, Long version) {}
    public record Dictionary(long id, String code, String name, DictionaryKind kind, boolean enabled, long version) {}
    public record DictionaryWrite(String code, String name, DictionaryKind kind, Boolean enabled, Long version) {}
    private final JdbcTemplate jdbc;
    private final AccountService accounts;
    private final ManagementAudit audit;
    public CatalogService(JdbcTemplate jdbc, AccountService accounts, ManagementAudit audit) {
        this.jdbc = jdbc; this.accounts = accounts; this.audit = audit;
    }
    private List<Source> allSources() {
        return jdbc.query("SELECT s.id, s.name, s.source_type, s.department_id, s.enabled, s.version, d.name FROM tb_data_source s LEFT JOIN tb_department d ON d.id = s.department_id ORDER BY s.id",
                (rs, row) -> new Source(rs.getLong(1), rs.getString(2), SourceType.valueOf(rs.getString(3)), rs.getObject(4, Long.class), rs.getBoolean(5), rs.getLong(6), rs.getString(7)));
    }
    private List<Dictionary> allDictionaries() {
        return jdbc.query("SELECT id, code, name, kind, enabled, version FROM tb_dictionary_item ORDER BY id",
                (rs, row) -> new Dictionary(rs.getLong(1), rs.getString(2), rs.getString(3), DictionaryKind.valueOf(rs.getString(4)), rs.getBoolean(5), rs.getLong(6)));
    }
    public List<Source> sources(Authentication auth) { accounts.administrator(auth); return allSources(); }
    public List<Dictionary> dictionaries(Authentication auth) { accounts.administrator(auth); return allDictionaries(); }
    private boolean canSeeSource(AccountService.Account account, Source source) {
        return source.departmentId() == null || account.canReadAll() || account.roles().contains("CENTER_ADMIN") || source.departmentId() == account.departmentId();
    }
    public Source source(Authentication auth, long id) {
        var account = accounts.current(auth);
        return allSources().stream().filter(source -> source.id() == id && canSeeSource(account, source)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "来源不存在"));
    }
    public Dictionary dictionary(Authentication auth, long id) {
        accounts.current(auth);
        return allDictionaries().stream().filter(item -> item.id() == id).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "分类不存在"));
    }
    public List<Source> sourceOptions(Authentication auth) {
        var account = accounts.current(auth);
        List<Long> enabledDepartments = jdbc.queryForList("SELECT id FROM tb_department WHERE enabled = 1", Long.class);
        return allSources().stream().filter(source -> source.enabled() && canSeeSource(account, source)
                && (source.departmentId() == null || enabledDepartments.contains(source.departmentId()))).toList();
    }
    public List<Dictionary> dictionaryOptions(Authentication auth, DictionaryKind kind) {
        accounts.current(auth);
        return allDictionaries().stream().filter(item -> item.enabled() && item.kind() == kind).toList();
    }
    @Transactional
    public Source saveSource(Authentication auth, Long id, SourceWrite input) {
        jdbc.queryForObject("SELECT id FROM tb_configuration_lock WHERE id = 1 FOR UPDATE", Long.class);
        var actor = accounts.administrator(auth);
        String name = InputChecks.text(input.name(), 100, "来源名称");
        InputChecks.require(input.sourceType() != null && input.enabled() != null, "请明确来源类型与启用状态");
        if (input.departmentId() != null) {
            InputChecks.require(jdbc.queryForObject("SELECT COUNT(*) FROM tb_department WHERE id = ?", Long.class, input.departmentId()) > 0, "归属部门不存在");
        }
        Source before = id == null ? null : source(auth, id);
        if (before == null) {
            id = JdbcIds.insert(jdbc, "INSERT INTO tb_data_source (name, source_type, department_id, enabled, created_by, modified_by) VALUES (?, ?, ?, ?, ?, ?)",
                    name, input.sourceType().name(), input.departmentId(), input.enabled(), actor.id(), actor.id());
        } else {
            InputChecks.version(input.version(), before.version());
            InputChecks.require(java.util.Objects.equals(before.departmentId(), input.departmentId()), "来源归属建立后不能更改，请停用后新建来源");
            jdbc.update("UPDATE tb_data_source SET name = ?, source_type = ?, enabled = ?, version = version + 1, modified_by = ? WHERE id = ?",
                    name, input.sourceType().name(), input.enabled(), actor.id(), id);
        }
        Source after = source(auth, id);
        audit.record(actor, "DATA_SOURCE", id, before, after);
        return after;
    }
    @Transactional
    public Dictionary saveDictionary(Authentication auth, Long id, DictionaryWrite input) {
        jdbc.queryForObject("SELECT id FROM tb_configuration_lock WHERE id = 1 FOR UPDATE", Long.class);
        var actor = accounts.administrator(auth);
        String name = InputChecks.text(input.name(), 100, "分类名称");
        String code = InputChecks.text(input.code(), 50, "分类编码");
        InputChecks.require(code.matches("[A-Za-z0-9_.-]+"), "分类编码只允许字母、数字、点、下划线和短横线");
        InputChecks.require(input.kind() != null && input.enabled() != null, "请明确字典类别与启用状态");
        Dictionary before = id == null ? null : dictionary(auth, id);
        if (before == null) {
            id = JdbcIds.insert(jdbc, "INSERT INTO tb_dictionary_item (kind, code, name, enabled, created_by, modified_by) VALUES (?, ?, ?, ?, ?, ?)",
                    input.kind().name(), code, name, input.enabled(), actor.id(), actor.id());
        } else {
            InputChecks.version(input.version(), before.version());
            InputChecks.require(before.kind() == input.kind() && before.code().equals(code), "字典类别与编码建立后不能更改");
            jdbc.update("UPDATE tb_dictionary_item SET name = ?, enabled = ?, version = version + 1, modified_by = ? WHERE id = ?",
                    name, input.enabled(), actor.id(), id);
        }
        Dictionary after = dictionary(auth, id);
        audit.record(actor, "DICTIONARY", id, before, after);
        return after;
    }
}
