package cn.governance;

import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AlertService {
    private final JdbcTemplate jdbc;
    private final IntakeService intake;
    private final RiskCalculation risk;
    private final ObjectMapper json;
    private final Clock clock;
    private final ManagementAudit audit;
    public AlertService(JdbcTemplate jdbc,IntakeService intake,RiskCalculation risk,ObjectMapper json,Clock clock,ManagementAudit audit) {this.jdbc=jdbc;this.intake=intake;this.risk=risk;this.json=json;this.clock=clock;this.audit=audit;}
    private void lock() {jdbc.queryForObject("SELECT id FROM tb_intake_lock WHERE id=1 FOR UPDATE",Long.class);}
    private LocalDateTime time(Object o) {return o instanceof Timestamp t?t.toLocalDateTime():(LocalDateTime)o;}
    private RiskCalculation.Snapshot snapshot(Object value) {try{return json.readValue(value.toString(),RiskCalculation.Snapshot.class);}catch(Exception e){throw new IllegalStateException(e);}}
    private String encode(Object value) {try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private Map<String,Object> alert(long id) {
        var rows=jdbc.queryForList("SELECT a.*,p.name FROM tb_alert a JOIN tb_person p ON p.id=a.person_id WHERE a.id=?",id);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return rows.getFirst();
    }
    public record Settings(int backlogDays,Long version) {}
    public Settings settings(Authentication a) {intake.analyst(a);return configuration();}
    private Settings configuration() {return jdbc.queryForObject("SELECT * FROM tb_alert_settings WHERE id=1",(r,n)->new Settings(r.getInt("backlog_days"),r.getLong("version")));}
    @Transactional public Settings configure(Authentication a,Settings input) {
        lock();var actor=intake.analyst(a);var before=configuration();InputChecks.version(input.version(),before.version());InputChecks.require(input.backlogDays()>=1 && input.backlogDays()<=365,"提醒阈值需1至365天");
        jdbc.update("UPDATE tb_alert_settings SET backlog_days=?,version=version+1 WHERE id=1",input.backlogDays());var after=configuration();audit.record(actor,"ALERT_SETTINGS",1,before,after);return after;
    }
    private boolean overdue(Map<String,Object> row,int days) {return BusinessTime.backlog(time(row.get("created_at")),row.get("reviewed_at")==null?null:time(row.get("reviewed_at")),row.get("state").toString(),days,clock);}
    public Object list(Authentication a,String level,String name,String keyword,String source,Long departmentId,LocalDateTime from,LocalDateTime to,String state,boolean backlog,int page,int size) {
        intake.analyst(a);InputChecks.require(page>=1 && page<=1000000 && size>=1 && size<=100,"分页无效");
        for(String q:List.of(level,name,keyword,source,state)) InputChecks.require(q.length()<=100,"查询过长");
        InputChecks.require(level.isEmpty() || Set.of("LOW","MEDIUM","HIGH").contains(level),"风险等级无效");
        InputChecks.require(state.isEmpty() || Set.of("OPEN","CLOSED").contains(state),"预警状态无效");
        InputChecks.require(from==null || to==null || !from.isAfter(to),"时间范围无效");
        var rows=jdbc.queryForList("SELECT a.*,p.name FROM tb_alert a JOIN tb_person p ON p.id=a.person_id WHERE (?='' OR a.level=?) AND LOCATE(?,p.name)>0 AND (?='' OR a.state=?) ORDER BY a.id DESC",level,level,name,state,state);
        int days=configuration().backlogDays();List<Map<String,Object>> result=new ArrayList<>();
        for(var row:rows) {
            LocalDateTime created=time(row.get("created_at"));if(from!=null && created.isBefore(from) || to!=null && created.isAfter(to)) continue;
            var basis=snapshot(row.get("snapshot"));
            if(!keyword.isEmpty() && basis.keywordHits().stream().noneMatch(h->h.ruleName().contains(keyword) || h.terms().stream().anyMatch(t->t.contains(keyword)))) continue;
            if((!source.isEmpty() || departmentId!=null) && basis.events().stream().flatMap(e->e.sources().stream()).noneMatch(s->s.source().contains(source) && (departmentId==null || s.departmentId()==departmentId))) continue;
            boolean due=overdue(row,days);if(backlog && !due) continue;
            row.put("backlog",due);row.remove("snapshot");row.remove("basis_hash");result.add(row);
        }
        long offset=(long)(page-1)*size;
        return Map.of("items",result.stream().skip(offset).limit(size).toList(),"total",result.size(),"backlogDays",days);
    }
    public Object detail(Authentication a,long id) {
        intake.analyst(a);var row=alert(id);row.put("snapshot",snapshot(row.get("snapshot")));row.put("backlog",overdue(row,configuration().backlogDays()));
        row.put("reviews",jdbc.queryForList("SELECT * FROM tb_alert_review WHERE alert_id=? ORDER BY id",id));
        row.put("current",risk.snapshot(((Number)row.get("person_id")).longValue()));return row;
    }
    public record Review(Long version,String conclusion,boolean close) {}
    @Transactional public Object review(Authentication a,long id,Review input) {
        lock();var actor=intake.analyst(a);var before=alert(id);InputChecks.version(input.version(),((Number)before.get("version")).longValue());
        if(!"OPEN".equals(before.get("state"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"预警已关闭");
        String conclusion=InputChecks.text(input.conclusion(),2000,"研判结论");var now=LocalDateTime.now(clock);
        JdbcIds.insert(jdbc,"INSERT INTO tb_alert_review(alert_id,action,conclusion,actor,created_at,snapshot) VALUES (?,?,?,?,?,?)",id,input.close()?"CLOSE":"ASSESS",conclusion,actor.username(),now,before.get("snapshot"));
        jdbc.update("UPDATE tb_alert SET reviewed_at=?,new_basis=0,state=?,closed_at=?,version=version+1 WHERE id=?",now,input.close()?"CLOSED":"OPEN",input.close()?now:null,id);
        audit.record(actor,"ALERT_REVIEW",id,before,Map.of("close",input.close(),"conclusion",conclusion));return detail(a,id);
    }
}
