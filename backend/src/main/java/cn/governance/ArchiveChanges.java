package cn.governance;

import java.util.*;
import java.time.LocalDateTime;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** All archive mutations share the intake lock with matching, duplicate review and risk publication. */
@Service
public class ArchiveChanges {
    private final JdbcTemplate jdbc;
    private final IntakeService intake;
    private final PersonMatching matching;
    private final RiskCalculation risk;
    private final ObjectMapper json;
    private final AccountService accountService;
    public ArchiveChanges(JdbcTemplate jdbc,IntakeService intake,PersonMatching matching,RiskCalculation risk,ObjectMapper json,AccountService accountService) {
        this.jdbc=jdbc;this.intake=intake;this.matching=matching;this.risk=risk;this.json=json;this.accountService=accountService;
    }
    public record Content(String personName,String idCard,String phone,String eventTime,String description) {}
    public record Request(Long version,String kind,Content payload,String reason) {}
    public record Decision(boolean approve,String reason) {}
    public record Profile(Long version,String name,String idCard,String phone,String reason) {}
    public record Move(Long version,Long targetVersion,long targetPersonId,String reason) {}
    private void lock() { jdbc.queryForObject("SELECT id FROM tb_intake_lock WHERE id=1 FOR UPDATE",Long.class); }
    private String encode(Object o) { try { return json.writeValueAsString(o); } catch(Exception e) { throw new IllegalStateException(e); } }
    private Content content(String s) { try { return json.readValue(s,Content.class); } catch(Exception e) { throw new IllegalStateException(e); } }
    private long number(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    private Content validate(Content c) {
        InputChecks.require(c!=null,"修正内容必填");
        String name=IdentityValues.clean(c.personName()),card=IdentityValues.clean(c.idCard()).toUpperCase(Locale.ROOT),phone=IdentityValues.clean(c.phone());
        InputChecks.require(name.length()<=100 && phone.length()<=32,"身份字段过长");
        InputChecks.require(card.isEmpty() || IdentityValues.card(card),"身份证号无效");
        String description=InputChecks.text(c.description(),10000,"问题描述");
        LocalDateTime time;
        try {time=LocalDateTime.parse(IdentityValues.clean(c.eventTime()).replace(' ','T'));}
        catch(Exception e) {throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"发生时间格式无效");}
        return new Content(name,card,phone,time.toString(),description);
    }
    public Object requests(Authentication a) {
        var actor=intakeActor(a);
        return jdbc.queryForList("SELECT * FROM tb_record_change WHERE (?=1 OR department_id=?) ORDER BY id DESC LIMIT 200",actor.canReadAll(),actor.departmentId());
    }
    private AccountService.Account intakeActor(Authentication a) {
        // Reuse record access policy without granting administrators implicit analyst powers.
        var actor=accountService.current(a);
        if(!actor.canReadAll() && !actor.canReadDepartment()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return actor;
    }
    @Transactional
    public Object request(Authentication a,long id,Request input) {
        lock();var before=intake.record(a,id);var actor=intakeActor(a);
        InputChecks.version(input.version(),number(before,"version"));
        InputChecks.require(before.get("effectiveEventId")!=null,"仅已归档记录可申请变更");
        InputChecks.require(Set.of("CORRECT","DELETE").contains(Objects.toString(input.kind(),"")),"申请类型无效");
        var payload="CORRECT".equals(input.kind())?validate(input.payload()):null;
        String reason=InputChecks.text(input.reason(),1000,"申请理由");
        InputChecks.require(jdbc.queryForObject("SELECT COUNT(*) FROM tb_record_change WHERE record_id=? AND state='PENDING'",Long.class,id)==0,"已有待审申请");
        long change=JdbcIds.insert(jdbc,"INSERT INTO tb_record_change(record_id,record_version,kind,payload,before_data,reason,requester,department_id) VALUES (?,?,?,?,?,?,?,?)",id,input.version(),input.kind(),payload==null?null:encode(payload),encode(before),reason,actor.username(),before.get("department_id"));
        return jdbc.queryForMap("SELECT * FROM tb_record_change WHERE id=?",change);
    }
    @Transactional
    public Object decide(Authentication a,long id,Decision input) {
        lock();var actor=intake.analyst(a);String reason=InputChecks.text(input.reason(),1000,"审核理由");
        var rows=jdbc.queryForList("SELECT * FROM tb_record_change WHERE id=?",id);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var change=rows.getFirst();
        if(!"PENDING".equals(change.get("state"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"申请已审核");
        long record=number(change,"record_id");var before=intake.record(a,record);
        if(input.approve()) {
            InputChecks.version(number(change,"record_version"),number(before,"version"));
            long oldPerson=number(before,"matched_person_id");
            jdbc.update("DELETE FROM tb_event_source_link WHERE record_id=?",record);
            if("DELETE".equals(change.get("kind"))) jdbc.update("UPDATE tb_input_record SET is_deleted=1,version=version+1,modified_by=? WHERE id=?",actor.username(),record);
            else {
                var c=validate(content(change.get("payload").toString()));
                jdbc.update("UPDATE tb_input_record SET party_name=?,party_id_card=?,party_phone=?,occurred_at=?,problem_description=?,matched_person_id=NULL,identity_review_state='PENDING',duplicate_review_state='PENDING',match_status=0,review_status=0,version=version+1,modified_by=? WHERE id=?",c.personName(),c.idCard(),c.phone(),LocalDateTime.parse(c.eventTime()),c.description(),actor.username(),record);
                matching.automatic(record,c.personName(),c.idCard(),c.phone());
            }
            risk.recompute(oldPerson);
        }
        var after=jdbc.queryForMap("SELECT * FROM tb_input_record WHERE id=?",record);
        jdbc.update("UPDATE tb_record_change SET state=?,reviewer=?,review_reason=?,reviewed_at=CURRENT_TIMESTAMP,after_data=? WHERE id=?",input.approve()?"APPROVED":"REJECTED",actor.username(),reason,encode(after),id);
        return jdbc.queryForMap("SELECT * FROM tb_record_change WHERE id=?",id);
    }
    public Object people(Authentication a,String q) {
        intake.analyst(a);InputChecks.require(q.length()<=100,"搜索过长");
        return jdbc.queryForList("SELECT * FROM tb_person WHERE merged_into IS NULL AND (LOCATE(?,name)>0 OR LOCATE(?,phone)>0 OR LOCATE(?,COALESCE(id_card,''))>0) ORDER BY id LIMIT 100",q,q,q);
    }
    private Map<String,Object> person(long id,Long version) {
        var rows=jdbc.queryForList("SELECT * FROM tb_person WHERE id=? AND merged_into IS NULL",id);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"档案不存在或已合并");
        var p=rows.getFirst();InputChecks.version(version,number(p,"version"));return p;
    }
    private void history(String kind,long from,long to,Long record,String reason,String actor,Object before,Object after) {
        JdbcIds.insert(jdbc,"INSERT INTO tb_archive_change(kind,from_person_id,to_person_id,record_id,reason,actor,before_data,after_data) VALUES (?,?,?,?,?,?,?,?)",kind,from,to,record,reason,actor,encode(before),encode(after));
    }
    @Transactional
    public Object profile(Authentication a,long id,Profile input) {
        lock();var actor=intake.analyst(a);var before=person(id,input.version());
        String reason=InputChecks.text(input.reason(),1000,"修正理由"),name=InputChecks.text(input.name(),100,"姓名"),card=IdentityValues.clean(input.idCard()).toUpperCase(Locale.ROOT),phone=IdentityValues.clean(input.phone());
        InputChecks.require(phone.length()<=32 && (card.isEmpty() || IdentityValues.card(card)),"身份格式无效");
        InputChecks.require(!card.isEmpty() || IdentityValues.phone(phone),"需有效身份证或手机号");
        InputChecks.require(jdbc.queryForObject("SELECT COUNT(*) FROM tb_person WHERE id<>? AND merged_into IS NULL AND ((?<>'' AND id_card=?) OR (?=1 AND phone=?))",Long.class,id,card,card,IdentityValues.phone(phone),phone)==0,"存在其他档案身份冲突");
        jdbc.update("UPDATE tb_person SET name=?,id_card=?,phone=?,version=version+1 WHERE id=?",name,card.isEmpty()?null:card,phone,id);
        var after=jdbc.queryForMap("SELECT * FROM tb_person WHERE id=?",id);
        history("PROFILE",id,id,null,reason,actor.username(),before,after);risk.recompute(id);return after;
    }
    @Transactional
    public Object merge(Authentication a,long id,Move input) {
        lock();var actor=intake.analyst(a);InputChecks.require(id!=input.targetPersonId(),"请选择不同档案");
        var before=person(id,input.version());var target=person(input.targetPersonId(),input.targetVersion());
        String reason=InputChecks.text(input.reason(),1000,"合并理由");
        // Identity differences are resolved explicitly by profile correction before merging.
        InputChecks.require(Objects.equals(before.get("name"),target.get("name")) &&
            (before.get("id_card")==null || target.get("id_card")==null || Objects.equals(before.get("id_card"),target.get("id_card"))) &&
            (target.get("id_card")!=null || IdentityValues.phone(Objects.toString(target.get("phone"),""))),"需姓名一致、身份证无冲突且目标档案身份依据可靠");
        var links=jdbc.queryForList("SELECT id FROM tb_input_record WHERE matched_person_id=? AND is_deleted=0",Long.class,id);
        jdbc.update("UPDATE tb_input_record SET matched_person_id=?,version=version+1 WHERE matched_person_id=? AND is_deleted=0",input.targetPersonId(),id);
        jdbc.update("UPDATE tb_effective_event SET person_id=? WHERE person_id=?",input.targetPersonId(),id);
        jdbc.update("UPDATE tb_person SET merged_into=?,id_card=NULL,version=version+1 WHERE id=?",input.targetPersonId(),id);
        jdbc.update("UPDATE tb_person SET id_card=COALESCE(id_card,?),version=version+1 WHERE id=?",before.get("id_card"),input.targetPersonId());
        history("MERGE",id,input.targetPersonId(),null,reason,actor.username(),Map.of("source",before,"target",target,"records",links),jdbc.queryForMap("SELECT * FROM tb_person WHERE id=?",input.targetPersonId()));
        risk.recompute(id);risk.recompute(input.targetPersonId());return Map.of("personId",input.targetPersonId());
    }
    @Transactional
    public Object split(Authentication a,long record,Move input) {
        lock();var actor=intake.analyst(a);var before=intake.record(a,record);
        InputChecks.version(input.version(),number(before,"version"));
        InputChecks.require(before.get("matched_person_id")!=null && before.get("effectiveEventId")!=null,"仅已归档记录可拆分");
        long old=number(before,"matched_person_id");InputChecks.require(old!=input.targetPersonId(),"请选择不同目标档案");
        person(input.targetPersonId(),input.targetVersion());String reason=InputChecks.text(input.reason(),1000,"拆分理由");
        jdbc.update("DELETE FROM tb_event_source_link WHERE record_id=?",record);
        jdbc.update("UPDATE tb_input_record SET duplicate_review_state='PENDING',version=version+1 WHERE id=?",record);
        var target=matching.person(input.targetPersonId());
        // The analyst supplies verified target identity through the selected archive; original source stays immutable.
        matching.review(record,target.id(),target.name(),Objects.toString(target.idCard(),""),target.phone());
        jdbc.update("UPDATE tb_person SET version=version+1 WHERE id IN (?,?)",old,target.id());
        var after=intake.record(a,record);history("SPLIT",old,target.id(),record,reason,actor.username(),before,after);
        risk.recompute(old);risk.recompute(target.id());return after;
    }
    public Object history(Authentication a) { intake.analyst(a);return jdbc.queryForList("SELECT * FROM tb_archive_change ORDER BY id DESC LIMIT 200"); }
}
