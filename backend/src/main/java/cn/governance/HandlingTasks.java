package cn.governance;

import java.time.*;
import java.util.*;
import java.io.IOException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class HandlingTasks {
    private final JdbcTemplate jdbc;
    private final AccountService accounts;
    private final IntakeService intake;
    private final RiskCalculation risk;
    private final ObjectMapper json;
    private final Clock clock;
    private final ManagementAudit audit;
    public HandlingTasks(JdbcTemplate jdbc,AccountService accounts,IntakeService intake,RiskCalculation risk,ObjectMapper json,Clock clock,ManagementAudit audit) {this.jdbc=jdbc;this.accounts=accounts;this.intake=intake;this.risk=risk;this.json=json;this.clock=clock;this.audit=audit;}
    private void lock() {jdbc.queryForObject("SELECT id FROM tb_intake_lock WHERE id=1 FOR UPDATE",Long.class);}
    private String encode(Object o) {try{return json.writeValueAsString(o);}catch(Exception e){throw new IllegalStateException(e);}}
    private Object decode(Object s) {try{return json.readTree(s.toString());}catch(Exception e){throw new IllegalStateException(e);}}
    private AccountService.Account reader(Authentication a) {
        var actor=accounts.current(a);if(!actor.canReadAll() && !actor.roles().contains("DEPARTMENT_HANDLER")) throw new ResponseStatusException(HttpStatus.FORBIDDEN);return actor;
    }
    private Map<String,Object> task(Authentication a,long id) {
        var actor=reader(a);var rows=jdbc.queryForList("SELECT t.*,d.name AS department_name FROM tb_handling_task t JOIN tb_department d ON d.id=t.department_id WHERE t.id=? AND (?=1 OR t.department_id=?)",id,actor.canReadAll(),actor.departmentId());
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);return rows.getFirst();
    }
    private void handler(Authentication a,Map<String,Object> task) {
        var actor=accounts.current(a);if(!actor.roles().contains("DEPARTMENT_HANDLER") || actor.departmentId()!=((Number)task.get("department_id")).longValue()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    public Object list(Authentication a,String state,int page,int size) {
        var actor=reader(a);InputChecks.require(state.isEmpty() || Set.of("ASSIGNED","RETURNED","SUBMITTED","CLOSED").contains(state),"任务状态无效");
        InputChecks.require(page>=1 && page<=1000000 && size>=1 && size<=100,"分页无效");
        String scope=" WHERE (?=1 OR t.department_id=?) AND (?='' OR t.state=?)";
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM tb_handling_task t"+scope,Long.class,actor.canReadAll(),actor.departmentId(),state,state);
        var items=jdbc.queryForList("SELECT t.id,t.alert_id,t.person_id,t.title,t.department_id,d.name AS department_name,t.state,t.version,t.deadline,t.created_at,t.submitted_at,t.closed_at FROM tb_handling_task t JOIN tb_department d ON d.id=t.department_id"+scope+" ORDER BY t.id DESC LIMIT ? OFFSET ?",actor.canReadAll(),actor.departmentId(),state,state,size,(long)(page-1)*size);
        return Map.of("items",items,"total",total);
    }
    public Object detail(Authentication a,long id) {
        var row=task(a,id);row.put("dispatch_snapshot",decode(row.get("dispatch_snapshot")));
        row.put("events",jdbc.queryForList("SELECT event_id FROM tb_task_event WHERE task_id=? ORDER BY event_id",Long.class,id));
        row.put("history",jdbc.queryForList("SELECT * FROM tb_task_note WHERE task_id=? ORDER BY id",id));
        row.put("attachments",jdbc.queryForList("SELECT id,note_id,file_name,stage,owner_username,created_at FROM tb_task_attachment WHERE task_id=? ORDER BY id",id));
        return row;
    }
    public record Dispatch(Long version,long personId,List<Long> eventIds,long departmentId,String deadline,String title,String instruction,String contact,String summary,List<Long> attachmentIds) {}
    private List<Long> ids(List<Long> ids,int limit) {
        if(ids==null) return List.of();InputChecks.require(ids.size()<=limit && ids.stream().allMatch(i->i!=null && i>0),"选择编号无效");return ids.stream().distinct().toList();
    }
    private String optional(String s,int max,String field) {String value=IdentityValues.clean(s);InputChecks.require(value.length()<=max,field+"过长");return value;}
    private Set<Long> effectiveIds(long personId) {return new HashSet<>(risk.evidence(personId).stream().map(RiskCalculation.EffectiveEvent::id).toList());}
    @Transactional public Object dispatch(Authentication a,long alertId,Dispatch input) {
        lock();var actor=intake.analyst(a);
        var alerts=jdbc.queryForList("SELECT * FROM tb_alert WHERE id=?",alertId);if(alerts.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var alert=alerts.getFirst();InputChecks.version(input.version(),((Number)alert.get("version")).longValue());
        InputChecks.require("OPEN".equals(alert.get("state")),"已关闭预警不能派发");
        InputChecks.require(((Number)alert.get("person_id")).longValue()==input.personId(),"人员与预警不一致");
        var events=ids(input.eventIds(),100);InputChecks.require(!events.isEmpty() && effectiveIds(input.personId()).containsAll(events),"需选择该人员至少一条当前有效事件");
        var departments=jdbc.queryForList("SELECT name FROM tb_department WHERE id=? AND enabled=1 AND department_type='DEPARTMENT'",String.class,input.departmentId());InputChecks.require(!departments.isEmpty(),"承办部门无效");
        LocalDateTime deadline;try{deadline=LocalDateTime.parse(input.deadline());}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"期限格式无效");}
        InputChecks.require(deadline.isAfter(LocalDateTime.now(clock)),"期限必须晚于当前时间");
        String title=InputChecks.text(input.title(),200,"任务标题"),instruction=InputChecks.text(input.instruction(),10000,"处置说明"),contact=optional(input.contact(),500,"授权联系方式"),summary=optional(input.summary(),10000,"授权摘要");
        var attachments=ids(input.attachmentIds(),20);validateAttachments(actor,attachments);
        var snapshot=new LinkedHashMap<String,Object>();snapshot.put("personId",input.personId());snapshot.put("eventIds",events);snapshot.put("departmentId",input.departmentId());snapshot.put("departmentName",departments.getFirst());snapshot.put("deadline",deadline);snapshot.put("title",title);snapshot.put("instruction",instruction);snapshot.put("contact",contact);snapshot.put("summary",summary);snapshot.put("attachmentIds",attachments);
        long id=JdbcIds.insert(jdbc,"INSERT INTO tb_handling_task(alert_id,person_id,department_id,title,instruction,contact,summary,deadline,dispatch_snapshot,dispatched_by,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",alertId,input.personId(),input.departmentId(),title,instruction,contact,summary,deadline,encode(snapshot),actor.username(),LocalDateTime.now(clock));
        for(long event:events) jdbc.update("INSERT INTO tb_task_event(task_id,event_id) VALUES (?,?)",id,event);
        for(long attachment:attachments) jdbc.update("UPDATE tb_task_attachment SET task_id=?,stage='DISPATCH' WHERE id=?",id,attachment);
        note(id,"DISPATCH",instruction,actor.username(),List.of());audit.record(actor,"TASK_DISPATCH",id,null,snapshot);return detail(a,id);
    }
    private void validateAttachments(AccountService.Account actor,List<Long> attachments) {
        for(long id:attachments) InputChecks.require(jdbc.queryForObject("SELECT COUNT(*) FROM tb_task_attachment WHERE id=? AND task_id IS NULL AND owner_username=?",Long.class,id,actor.username())==1,"附件无效或已使用");
    }
    private long note(long task,String action,String content,String actor,List<Long> completed) {
        return JdbcIds.insert(jdbc,"INSERT INTO tb_task_note(task_id,action,content,actor,created_at,completed_events) VALUES (?,?,?,?,?,?)",task,action,content,actor,LocalDateTime.now(clock),encode(completed));
    }
    public record Progress(Long version,String content,boolean submit,List<Long> attachmentIds) {}
    @Transactional public Object progress(Authentication a,long id,Progress input) {
        lock();var actor=reader(a);var before=task(a,id);handler(a,before);InputChecks.version(input.version(),((Number)before.get("version")).longValue());
        if(!Set.of("ASSIGNED","RETURNED").contains(before.get("state"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"当前阶段不可跟进或重复提交");
        String content=InputChecks.text(input.content(),10000,input.submit()?"处理结果":"跟进内容");
        var attachments=ids(input.attachmentIds(),20);validateAttachments(actor,attachments);
        long note=note(id,input.submit()?"SUBMIT":"PROGRESS",content,actor.username(),List.of());
        for(long attachment:attachments) jdbc.update("UPDATE tb_task_attachment SET task_id=?,note_id=?,stage='FOLLOWUP' WHERE id=?",id,note,attachment);
        jdbc.update("UPDATE tb_handling_task SET state=?,submitted_at=?,version=version+1 WHERE id=?",input.submit()?"SUBMITTED":before.get("state"),input.submit()?LocalDateTime.now(clock):before.get("submitted_at"),id);
        audit.record(actor,"TASK_PROGRESS",id,Map.of("state",before.get("state")),Map.of("noteId",note,"submit",input.submit()));return detail(a,id);
    }
    public record Review(Long version,String decision,String reason,List<Long> completeEventIds) {}
    @Transactional public Object review(Authentication a,long id,Review input) {
        lock();var actor=intake.analyst(a);var before=task(a,id);InputChecks.version(input.version(),((Number)before.get("version")).longValue());
        if(!"SUBMITTED".equals(before.get("state"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"仅待审任务可审核");
        InputChecks.require(Set.of("RETURN","CLOSE").contains(Objects.toString(input.decision(),"")),"审核结论无效");String reason=InputChecks.text(input.reason(),2000,"审核理由");
        var completed=ids(input.completeEventIds(),100);InputChecks.require("CLOSE".equals(input.decision()) || completed.isEmpty(),"退回不能办结事件");
        var linked=jdbc.queryForList("SELECT event_id FROM tb_task_event WHERE task_id=?",Long.class,id);
        InputChecks.require(linked.containsAll(completed),"只能办结本任务明确关联的事件");
        if("CLOSE".equals(input.decision())) {
            // Task 28 will add the explicit confirmation flow; until then reject stale archive relationships.
            if(!effectiveIds(((Number)before.get("person_id")).longValue()).containsAll(linked)) throw new ResponseStatusException(HttpStatus.CONFLICT,"关联归档已经变化，请先核实当前人员和事件");
            for(long event:completed) {
                jdbc.update("UPDATE tb_effective_event SET is_completed=1,completed_at=? WHERE id=?",LocalDateTime.now(clock),event);
                jdbc.update("UPDATE tb_input_record r JOIN tb_event_source_link l ON l.record_id=r.id SET r.is_completed=1,r.version=r.version+1,r.modified_by=? WHERE l.effective_event_id=? AND r.is_deleted=0",actor.username(),event);
            }
        }
        long note=note(id,input.decision(),reason,actor.username(),completed);
        jdbc.update("UPDATE tb_handling_task SET state=?,closed_at=?,submitted_at=NULL,version=version+1 WHERE id=?","CLOSE".equals(input.decision())?"CLOSED":"RETURNED","CLOSE".equals(input.decision())?LocalDateTime.now(clock):null,id);
        audit.record(actor,"TASK_REVIEW",id,Map.of("state",before.get("state")),Map.of("noteId",note,"decision",input.decision(),"completedEvents",completed));return detail(a,id);
    }
    /** Uploads are private until explicitly selected in dispatch/progress. */
    @Transactional public Object upload(Authentication a,MultipartFile file) throws IOException {
        lock();var actor=reader(a);InputChecks.require(!file.isEmpty() && file.getSize()<=5*1024*1024,"附件需1字节至5MB");
        String name=Objects.toString(file.getOriginalFilename(),"附件").replace('\\','/');name=name.substring(name.lastIndexOf('/')+1).replaceAll("[\\p{Cntrl}]","");InputChecks.require(!name.isBlank() && name.length()<=200,"文件名无效");
        long id=JdbcIds.insert(jdbc,"INSERT INTO tb_task_attachment(owner_username,stage,file_name,content,created_at) VALUES (?,'PRIVATE',?,?,?)",actor.username(),name,file.getBytes(),LocalDateTime.now(clock));return Map.of("id",id,"fileName",name);
    }
    public record Download(String fileName,byte[] content) {}
    public Download download(Authentication a,long id) {
        var actor=reader(a);var rows=jdbc.queryForList("SELECT * FROM tb_task_attachment WHERE id=?",id);if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var row=rows.getFirst();
        if(row.get("task_id")==null) {if(!actor.username().equals(row.get("owner_username"))) throw new ResponseStatusException(HttpStatus.NOT_FOUND);}
        else task(a,((Number)row.get("task_id")).longValue());
        return new Download(row.get("file_name").toString(),(byte[])row.get("content"));
    }
}
