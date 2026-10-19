package cn.governance;

import java.util.*;
import java.time.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IntakeService {
    private final JdbcTemplate jdbc;
    private final AccountService accounts;
    private final CatalogService catalog;
    private final ObjectMapper json;
    private final ManagementAudit audit;
    private final PersonMatching matching;
    private final DuplicateScreening duplicates;
    private final EventConsolidation events;
    private final RiskCalculation risk;
    private final KeywordRecognition keywords;
    public IntakeService(JdbcTemplate jdbc,AccountService accounts,CatalogService catalog,ObjectMapper json,ManagementAudit audit,PersonMatching matching,DuplicateScreening duplicates,EventConsolidation events,RiskCalculation risk,KeywordRecognition keywords) {
        this.jdbc=jdbc;this.accounts=accounts;this.catalog=catalog;this.json=json;this.audit=audit;this.matching=matching;this.duplicates=duplicates;this.events=events;this.risk=risk;this.keywords=keywords;
    }
    public Object departments(Authentication auth) {
        analyst(auth);return jdbc.queryForList("SELECT id,name FROM tb_department WHERE enabled=1 AND department_type='DEPARTMENT' ORDER BY id");
    }
    public record Entry(String personName,String idCard,String phone,String eventTime,String description,Long sourceId,Long categoryId,Long disputeId,Long departmentId) {}
    public record Draft(long id,Entry payload,long version,Long recordId) {}
    public record Version(Long version) {}
    public record DraftWrite(Entry payload,Long version) {}
    public record Review(Long version,Long personId,String personName,String idCard,String phone,String reason) {}
    private void lock() { jdbc.queryForObject("SELECT id FROM tb_intake_lock WHERE id=1 FOR UPDATE",Long.class); }
    private AccountService.Account reporter(Authentication auth) {
        var actor=accounts.current(auth);
        if(!actor.canReadAll() && !actor.canReadDepartment()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return actor;
    }
    public AccountService.Account analyst(Authentication auth) {
        var actor=accounts.current(auth);
        if(!actor.canReadAll()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return actor;
    }
    private String encode(Object value) { try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException(e); } }
    private Entry decode(String value) { try { return json.readValue(value,Entry.class); } catch(Exception e) { throw new IllegalStateException(e); } }
    public List<Draft> drafts(Authentication auth) {
        var actor=reporter(auth);
        return jdbc.query("SELECT * FROM tb_event_draft WHERE owner_id=? AND record_id IS NULL ORDER BY id DESC",(r,n)->new Draft(r.getLong("id"),decode(r.getString("payload")),r.getLong("version"),r.getObject("record_id",Long.class)),actor.id());
    }
    public Draft draft(Authentication auth,long id) {
        var actor=reporter(auth);
        return jdbc.query("SELECT * FROM tb_event_draft WHERE id=? AND owner_id=?",(r,n)->new Draft(r.getLong("id"),decode(r.getString("payload")),r.getLong("version"),r.getObject("record_id",Long.class)),id,actor.id()).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    @Transactional
    public Draft save(Authentication auth,Long id,Entry entry,Long version) {
        lock();var actor=reporter(auth);
        InputChecks.require(entry!=null && encode(entry).length()<=30000,"草稿过大");
        Draft before=id==null?null:draft(auth,id);
        if(before==null) id=JdbcIds.insert(jdbc,"INSERT INTO tb_event_draft(owner_id,payload) VALUES (?,?)",actor.id(),encode(entry));
        else {
            InputChecks.version(version,before.version());InputChecks.require(before.recordId()==null,"提交后不能修改草稿");
            jdbc.update("UPDATE tb_event_draft SET payload=?,version=version+1 WHERE id=?",encode(entry),id);
        }
        var after=draft(auth,id);audit.record(actor,"EVENT_DRAFT",id,before,after);return after;
    }
    @Transactional
    public void delete(Authentication auth,long id,Long version) {
        lock();var before=draft(auth,id);InputChecks.version(version,before.version());InputChecks.require(before.recordId()==null,"提交后不能删除");
        jdbc.update("DELETE FROM tb_event_draft WHERE id=?",id);audit.record(reporter(auth),"EVENT_DRAFT",id,before,Map.of("deleted",true));
    }
    private String dictionary(Authentication auth,Long id,CatalogService.DictionaryKind kind) {
        return catalog.dictionaryOptions(auth,kind).stream().filter(d->Objects.equals(d.id(),id)).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.BAD_REQUEST,"请选择启用的标准分类")).name();
    }
    @Transactional
    public Map<String,Object> submit(Authentication auth,long id,Long version) {
        lock();jdbc.queryForObject("SELECT id FROM tb_configuration_lock WHERE id=1 FOR UPDATE",Long.class);
        var actor=reporter(auth);var draft=draft(auth,id);
        if(draft.recordId()!=null) return record(auth,draft.recordId());
        InputChecks.version(version,draft.version());var e=draft.payload();
        String name=IdentityValues.clean(e.personName()), card=IdentityValues.clean(e.idCard()).toUpperCase(Locale.ROOT), phone=IdentityValues.clean(e.phone());
        InputChecks.require(name.length()<=100 && phone.length()<=32,"身份字段过长");
        InputChecks.require(card.isEmpty() || IdentityValues.card(card),"身份证号无效");
        String description=InputChecks.text(IdentityValues.clean(e.description()).replaceAll("\\h+"," "),10000,"问题描述");
        LocalDateTime time;
        try { time=LocalDateTime.parse(IdentityValues.clean(e.eventTime()).replace(' ','T')); } catch(Exception ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"发生时间格式无效"); }
        long department=actor.departmentId();
        if(actor.canReadAll()) { InputChecks.require(e.departmentId()!=null,"请选择代录部门");department=e.departmentId(); }
        else InputChecks.require(e.departmentId()==null || e.departmentId()==department,"不能替其他部门录入");
        InputChecks.require(jdbc.queryForObject("SELECT COUNT(*) FROM tb_department WHERE id=? AND enabled=1 AND department_type='DEPARTMENT'",Long.class,department)==1,"部门无效");
        var source=catalog.sourceOptions(auth).stream().filter(s->Objects.equals(s.id(),e.sourceId())).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.BAD_REQUEST,"请选择启用的数据来源"));
        InputChecks.require(source.departmentId()==null || source.departmentId()==department,"来源与上报部门不符");
        String category=dictionary(auth,e.categoryId(),CatalogService.DictionaryKind.EVENT_CATEGORY), dispute=dictionary(auth,e.disputeId(),CatalogService.DictionaryKind.DISPUTE_TYPE);
        long recordId=JdbcIds.insert(jdbc,"INSERT INTO tb_input_record(occurred_at,problem_description,party_name,party_id_card,party_phone,event_category,dispute_type,event_source,department_id,created_by,modified_by) VALUES (?,?,?,?,?,?,?,?,?,?,?)",time,description,name,card,phone,category,dispute,source.name(),department,actor.username(),actor.username());
        jdbc.update("INSERT INTO tb_record_provenance(record_id,draft_id,original_payload,source_id,category_id,dispute_id,special_report,actor_id) VALUES (?,?,?,?,?,?,?,?)",recordId,id,encode(e),source.id(),e.categoryId(),e.disputeId(),actor.roles().contains("SPECIAL_REPORTER"),actor.id());
        jdbc.update("UPDATE tb_event_draft SET record_id=?,version=version+1 WHERE id=?",recordId,id);
        matching.automatic(recordId,name,card,phone);
        var after=record(auth,recordId);audit.record(actor,"EVENT_SUBMISSION",recordId,null,after);return after;
    }
    public Map<String,Object> record(Authentication auth,long id) {
        var actor=reporter(auth);
        var rows=jdbc.queryForList("SELECT r.*,p.original_payload,p.source_id,p.category_id,p.dispute_id,p.special_report,p.actor_id FROM tb_input_record r LEFT JOIN tb_record_provenance p ON p.record_id=r.id WHERE r.id=? AND r.is_deleted=0 AND (?=1 OR r.department_id=?)",id,actor.canReadAll(),actor.departmentId());
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var result=rows.getFirst(); if(result.get("original_payload")!=null) result.put("original_payload",decode(result.get("original_payload").toString()));
        result.put("effectiveEventId",events.eventId(id));
        result.put("keywordHits",keywords.record(id));
        if(actor.canReadAll()) {
            result.put("duplicateHistory",events.history(id));
            result.put("effectiveCount",result.get("matched_person_id")==null?0:events.count(((Number)result.get("matched_person_id")).longValue()));
        }return result;
    }
    public List<Map<String,Object>> reviews(Authentication auth) {
        analyst(auth);return jdbc.queryForList("SELECT id,party_name,party_id_card,party_phone,problem_description,version FROM tb_input_record WHERE identity_review_state='PENDING' AND is_deleted=0 ORDER BY id LIMIT 100");
    }
    @Transactional
    public Map<String,Object> review(Authentication auth,long id,Review input) {
        lock();var actor=analyst(auth);var before=record(auth,id);
        InputChecks.version(input.version(),((Number)before.get("version")).longValue());
        if(!"PENDING".equals(before.get("identity_review_state"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"身份已审核");
        String reason=InputChecks.text(input.reason(),1000,"审核理由");
        long personId=matching.review(id,nullOrPositive(input.personId()),IdentityValues.clean(input.personName()),IdentityValues.clean(input.idCard()).toUpperCase(Locale.ROOT),IdentityValues.clean(input.phone()));
        var after=record(auth,id);audit.record(actor,"IDENTITY_REVIEW",id,before,Map.of("record",after,"reason",reason,"personId",personId));return after;
    }
    private Long nullOrPositive(Long id) { InputChecks.require(id==null || id>0,"档案编号无效");return id; }
    public List<PersonMatching.Person> people(Authentication auth,String q) { analyst(auth);return matching.people(q); }
    public Map<String,Object> person(Authentication auth,long id) {
        analyst(auth);return Map.of("person",matching.person(id),"events",jdbc.queryForList("SELECT id,party_name,party_phone,occurred_at,problem_description,identity_review_state,duplicate_review_state,version FROM tb_input_record WHERE matched_person_id=? AND is_deleted=0 ORDER BY occurred_at DESC",id),"effectiveCount",events.count(id));
    }
    public Object keywordRules(Authentication auth) { analyst(auth);return risk.activeRules().keywords(); }
    public record Tags(Long version,String tags,String reason) {}
    @Transactional
    public Map<String,Object> tags(Authentication auth,long id,Tags input) {
        lock();var actor=analyst(auth);var before=record(auth,id);
        InputChecks.version(input.version(),((Number)before.get("version")).longValue());
        String reason=InputChecks.text(input.reason(),1000,"标注理由");
        String tags=IdentityValues.clean(input.tags());InputChecks.require(tags.length()<=500,"标签最多500字符");
        jdbc.update("UPDATE tb_input_record SET keyword_tags=?,modified_by=?,version=version+1 WHERE id=?",tags,actor.username(),id);
        if(before.get("matched_person_id")!=null) risk.recompute(((Number)before.get("matched_person_id")).longValue());
        var after=record(auth,id);audit.record(actor,"EVENT_TAGS",id,before,Map.of("record",after,"reason",reason));return after;
    }
    public Object duplicateReviews(Authentication auth) {
        analyst(auth);
        return jdbc.queryForList("SELECT r.id,r.party_name,r.problem_description,r.occurred_at,r.version,r.matched_person_id FROM tb_input_record r LEFT JOIN tb_event_source_link l ON l.record_id=r.id WHERE l.record_id IS NULL AND r.identity_review_state='CONFIRMED' AND r.is_deleted=0 ORDER BY r.id LIMIT 100");
    }
    public record DuplicateReview(Long version,String decision,Long targetRecordId,String reason) {}
    @Transactional
    public Map<String,Object> duplicateReview(Authentication auth,long id,DuplicateReview input) {
        lock();var actor=analyst(auth);var before=record(auth,id);
        InputChecks.version(input.version(),((Number)before.get("version")).longValue());
        InputChecks.require("CONFIRMED".equals(before.get("identity_review_state")),"请先确认身份");
        if(events.eventId(id)!=null) throw new ResponseStatusException(HttpStatus.CONFLICT,"重复审核已经明确，请使用后续归档修正流程");
        String reason=InputChecks.text(input.reason(),1000,"重复审核理由");
        InputChecks.require("INDEPENDENT".equals(input.decision()) || "MERGE".equals(input.decision()),"审核结论无效");
        if("INDEPENDENT".equals(input.decision())) events.independent(id,actor.username(),reason);
        else {
            InputChecks.require(input.targetRecordId()!=null && input.targetRecordId()!=id,"请选择其他目标记录");
            var target=record(auth,input.targetRecordId());
            InputChecks.require("CONFIRMED".equals(target.get("identity_review_state")) && Objects.equals(before.get("matched_person_id"),target.get("matched_person_id")),"只能合并身份明确且同人的事件");
            long event=events.independent(input.targetRecordId(),actor.username(),reason);
            events.attach(id,event,"MERGED",actor.username(),reason);
        }
        risk.recompute(((Number)before.get("matched_person_id")).longValue());
        var after=record(auth,id);audit.record(actor,"DUPLICATE_REVIEW",id,before,Map.of("record",after,"reason",reason));return after;
    }
    public Object risk(Authentication auth,long id) {
        analyst(auth);matching.person(id);
        com.fasterxml.jackson.databind.node.ObjectNode result=json.valueToTree(risk.snapshot(id));
        Map<Long,String> departments=new HashMap<>();
        jdbc.query("SELECT id,name FROM tb_department",rs->{departments.put(rs.getLong("id"),rs.getString("name"));});
        for(var event:result.withArray("events")) {
            for(var source:event.withArray("sources")) {
                ((com.fasterxml.jackson.databind.node.ObjectNode)source).put("departmentName",departments.getOrDefault(source.path("departmentId").asLong(),"未知部门"));
            }
        }
        return result;
    }
    public List<DuplicateScreening.Candidate> candidates(Authentication auth,long id) { analyst(auth);record(auth,id);return duplicates.candidates(id); }
    public DuplicateScreening.Settings settings(Authentication auth) { analyst(auth);return duplicates.settings(); }
    @Transactional
    public DuplicateScreening.Settings settings(Authentication auth,DuplicateScreening.Settings input) {
        lock();var actor=analyst(auth);var before=duplicates.settings();InputChecks.version(input.version(),before.version());
        InputChecks.require(input.windowDays()>=0 && input.windowDays()<=365 && Double.isFinite(input.threshold()) && input.threshold()>0 && input.threshold()<=1,"时间窗口需0至365天，相似度需大于0且不超过1");
        jdbc.update("UPDATE tb_duplicate_settings SET window_days=?,threshold=?,version=version+1 WHERE id=1",input.windowDays(),input.threshold());
        jdbc.queryForList("SELECT id FROM tb_input_record WHERE identity_review_state='CONFIRMED' AND is_deleted=0",Long.class).forEach(duplicates::screen);
        jdbc.queryForList("SELECT id FROM tb_person",Long.class).forEach(risk::recompute);
        var after=duplicates.settings();audit.record(actor,"DUPLICATE_SETTINGS",1,before,after);return after;
    }
}
