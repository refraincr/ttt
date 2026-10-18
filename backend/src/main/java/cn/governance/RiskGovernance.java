package cn.governance;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@RestController
@RequestMapping("/api/risk-governance")
public class RiskGovernance {
    private final JdbcTemplate jdbc;
    private final IntakeService intake;
    private final RiskCalculation risk;
    private final KeywordRecognition keywords;
    private final ObjectMapper json;
    private final ManagementAudit audit;
    public RiskGovernance(JdbcTemplate jdbc,IntakeService intake,RiskCalculation risk,KeywordRecognition keywords,ObjectMapper json,ManagementAudit audit) {
        this.jdbc=jdbc;this.intake=intake;this.risk=risk;this.keywords=keywords;this.json=json;this.audit=audit;
    }
    private void lock(){jdbc.queryForObject("SELECT id FROM tb_intake_lock WHERE id=1 FOR UPDATE",Long.class);}
    private String encode(Object o){try{return json.writeValueAsString(o);}catch(Exception e){throw new IllegalStateException(e);}}
    public record Exclusion(long personId,long eventId,long ruleId,String fingerprint,String reason){}
    public record Adjustment(long personId,String level,String fingerprint,String reason){}
    @PostMapping("/exclusions") @Transactional
    public Object exclude(Authentication a,@RequestBody Exclusion input){
        lock();var actor=intake.analyst(a);var snapshot=risk.calculate(input.personId());
        var hit=snapshot.keywordHits().stream().filter(h->Objects.equals(h.eventId(),input.eventId())&&h.ruleId()==input.ruleId()).findFirst().orElseThrow(()->new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"命中已变化，请刷新"));
        InputChecks.require(Objects.equals(input.fingerprint(),keywords.fingerprint(hit)),"命中已变化，请刷新");
        String reason=InputChecks.text(input.reason(),1000,"排除理由");
        long id=JdbcIds.insert(jdbc,"INSERT INTO tb_keyword_exclusion(event_id,rule_id,fingerprint,reason,actor) VALUES (?,?,?,?,?)",input.eventId(),input.ruleId(),keywords.fingerprint(hit),reason,actor.username());
        risk.recompute(input.personId());audit.record(actor,"KEYWORD_EXCLUSION",id,null,input);return risk.snapshot(input.personId());
    }
    @GetMapping("/people/{id}")
    public Object detail(Authentication a,@PathVariable long id){
        intake.risk(a,id);var snapshot=risk.calculate(id);
        return Map.of("snapshot",snapshot,"fingerprint",risk.basis(snapshot.events(),snapshot.keywordHits(),risk.activeRules()),"hits",snapshot.keywordHits().stream().map(h->Map.of("hit",h,"fingerprint",keywords.fingerprint(h))).toList(),"adjustments",jdbc.queryForList("SELECT * FROM tb_risk_adjustment WHERE person_id=? ORDER BY id DESC",id),"exclusions",jdbc.queryForList("SELECT x.* FROM tb_keyword_exclusion x JOIN tb_effective_event e ON e.id=x.event_id WHERE e.person_id=? ORDER BY x.id DESC",id),"history",jdbc.queryForList("SELECT * FROM tb_risk_history WHERE person_id=? ORDER BY id DESC LIMIT 100",id));
    }
    @PostMapping("/adjustments") @Transactional
    public Object adjust(Authentication a,@RequestBody Adjustment input){
        lock();var actor=intake.analyst(a);intake.risk(a,input.personId());var snapshot=risk.calculate(input.personId());
        InputChecks.require(Set.of("LOW","MEDIUM","HIGH").contains(Objects.toString(input.level(),"")),"等级无效");
        InputChecks.require(!snapshot.specialFloor()||!"LOW".equals(input.level()),"特殊来源至少中风险");
        String fingerprint=risk.basis(snapshot.events(),snapshot.keywordHits(),risk.activeRules());
        InputChecks.require(fingerprint.equals(input.fingerprint()),"风险依据已变化，请刷新");
        long id=JdbcIds.insert(jdbc,"INSERT INTO tb_risk_adjustment(person_id,level,fingerprint,reason,actor) VALUES (?,?,?,?,?)",input.personId(),input.level(),fingerprint,InputChecks.text(input.reason(),1000,"调整理由"),actor.username());
        risk.recompute(input.personId());audit.record(actor,"RISK_ADJUSTMENT",id,null,input);return risk.snapshot(input.personId());
    }
    @GetMapping("/rules") public Object rules(Authentication a){intake.analyst(a);return Map.of("active",risk.activeRules(),"releases",jdbc.queryForList("SELECT * FROM tb_rule_release ORDER BY id DESC"),"jobs",jdbc.queryForList("SELECT * FROM tb_recalculation ORDER BY id DESC"));}
    public record Draft(Long id,Long version,RiskCalculation.Rules rules){}
    @PostMapping("/rules") @Transactional
    public Object save(Authentication a,@RequestBody Draft d){
        lock();var actor=intake.analyst(a);var r=d.rules();InputChecks.require(r!=null&&r.medium()>0&&r.high()>r.medium()&&r.keywords()!=null&&r.keywords().size()<=100,"次数阈值或关键词无效");
        var ids=new HashSet<Long>();
        for(var k:r.keywords()) {InputChecks.require(k.id()>0&&ids.add(k.id())&&k.terms()!=null&&!k.terms().isEmpty()&&k.terms().size()<=10,"关键词编号或组合无效");InputChecks.text(k.name(),100,"规则名");for(var t:k.terms())InputChecks.text(t,100,"关键词");}
        // Server owns per-rule revisions: unrelated edits preserve exclusion fingerprints.
        var old=risk.activeRules().keywords();
        var normalized=r.keywords().stream().map(k->{var before=old.stream().filter(o->o.id()==k.id()).findFirst();long v=before.map(o->o.name().equals(k.name())&&o.terms().equals(k.terms())&&o.enabled()==k.enabled()?o.version():o.version()+1).orElse(1L);return new KeywordRecognition.Rule(k.id(),k.name(),k.terms(),k.enabled(),v);}).toList();
        r=new RiskCalculation.Rules(r.medium(),r.high(),normalized);
        long id;
        if(d.id()==null)id=JdbcIds.insert(jdbc,"INSERT INTO tb_rule_release(payload,actor) VALUES (?,?)",encode(r),actor.username());
        else {id=d.id();var before=jdbc.queryForMap("SELECT * FROM tb_rule_release WHERE id=?",id);InputChecks.require("DRAFT".equals(before.get("state")),"已发布规则不能修改");InputChecks.version(d.version(),((Number)before.get("version")).longValue());jdbc.update("UPDATE tb_rule_release SET payload=?,version=version+1 WHERE id=?",encode(r),id);}
        audit.record(actor,"RULE_DRAFT",id,null,r);return jdbc.queryForMap("SELECT * FROM tb_rule_release WHERE id=?",id);
    }
    private RiskCalculation.Rules config(long id){try{return json.readValue(jdbc.queryForObject("SELECT payload FROM tb_rule_release WHERE id=?",String.class,id),RiskCalculation.Rules.class);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}}
    @PostMapping("/rules/{id}/preview") @Transactional
    public Object preview(Authentication a,@PathVariable long id){
        lock();intake.analyst(a);var config=config(id);List<Object> changes=new ArrayList<>();
        int up=0,down=0;
        long preview=JdbcIds.insert(jdbc,"INSERT INTO tb_rule_preview(release_id,draft_version,evidence) SELECT id,version,'{}' FROM tb_rule_release WHERE id=?",id);
        for(long person:jdbc.queryForList("SELECT id FROM tb_person ORDER BY id",Long.class)){
            var before=risk.snapshot(person);var after=risk.calculate(person,config,"RELEASE-"+id);
            if(!before.finalLevel().equals(after.finalLevel())){if(rank(after.finalLevel())>rank(before.finalLevel()))up++;else down++;}
            jdbc.update("INSERT INTO tb_rule_preview_person(preview_id,person_id,before_data,after_data) VALUES (?,?,?,?)",preview,person,encode(before),encode(after));
            changes.add(Map.of("personId",person,"before",before.finalLevel(),"after",after.finalLevel()));
        }
        var result=Map.of("previewId",preview,"up",up,"down",down,"affected",up+down,"data",changes,"rules",config);
        jdbc.update("UPDATE tb_rule_preview SET evidence=? WHERE id=?",encode(result),preview);return result;
    }
    @GetMapping("/previews/{id}/people") public Object previewPeople(Authentication a,@PathVariable long id,@RequestParam(defaultValue="1") int page){intake.analyst(a);InputChecks.require(page>0&&page<=1000000,"分页无效");return jdbc.queryForList("SELECT * FROM tb_rule_preview_person WHERE preview_id=? ORDER BY person_id LIMIT 20 OFFSET ?",id,(long)(page-1)*20);}
    private int rank(String level){return List.of("LOW","MEDIUM","HIGH").indexOf(level);}
    @PostMapping("/rules/{id}/publish") @Transactional
    public Object publish(Authentication a,@PathVariable long id,@RequestBody IntakeService.Version version){
        lock();var actor=intake.analyst(a);var row=jdbc.queryForMap("SELECT * FROM tb_rule_release WHERE id=?",id);InputChecks.version(version.version(),((Number)row.get("version")).longValue());InputChecks.require("DRAFT".equals(row.get("state")),"规则已发布");
        InputChecks.require(jdbc.queryForObject("SELECT COUNT(*) FROM tb_rule_preview WHERE release_id=? AND draft_version=?",Long.class,id,version.version())>0,"请先预览当前草稿");
        jdbc.update("UPDATE tb_rule_release SET state='HISTORICAL' WHERE state='PUBLISHED'");jdbc.update("UPDATE tb_rule_release SET state='PUBLISHED' WHERE id=?",id);
        long job=JdbcIds.insert(jdbc,"INSERT INTO tb_recalculation(release_id,total) SELECT ?,COUNT(*) FROM tb_person",id);audit.record(actor,"RULE_PUBLICATION",id,null,row);return Map.of("jobId",job);
    }
    @PostMapping("/jobs/{id}/retry") @Transactional public Object retry(Authentication a,@PathVariable long id){lock();intake.analyst(a);InputChecks.require(jdbc.update("UPDATE tb_recalculation SET state='PENDING',error=NULL WHERE id=? AND state='FAILED'",id)==1,"仅失败任务可重试");return Map.of("id",id);}
}

@Component
class RiskRecalculationWorker {
    private final JdbcTemplate jdbc;private final RiskCalculation risk;
    RiskRecalculationWorker(JdbcTemplate jdbc,RiskCalculation risk){this.jdbc=jdbc;this.risk=risk;}
    @Scheduled(fixedDelay=1000) @Transactional public void tick(){
        jdbc.queryForObject("SELECT id FROM tb_intake_lock WHERE id=1 FOR UPDATE",Long.class);
        var jobs=jdbc.queryForList("SELECT * FROM tb_recalculation WHERE state='PENDING' ORDER BY id LIMIT 1");if(jobs.isEmpty())return;
        var job=jobs.getFirst();long id=((Number)job.get("id")).longValue();
        if(!risk.activeVersion().equals("RELEASE-"+job.get("release_id"))){jdbc.update("UPDATE tb_recalculation SET state='SUPERSEDED' WHERE id=?",id);return;}
        try {long completed=((Number)job.get("completed")).longValue();var people=jdbc.queryForList("SELECT id FROM tb_person ORDER BY id LIMIT 200 OFFSET ?",Long.class,completed);
            for(long person:people)risk.recompute(person);
            jdbc.update("UPDATE tb_recalculation SET completed=completed+?,state=?,total=(SELECT COUNT(*) FROM tb_person),error=NULL WHERE id=?",people.size(),people.size()<200?"COMPLETED":"PENDING",id);
        }catch(Exception e){jdbc.update("UPDATE tb_recalculation SET state='FAILED',error=? WHERE id=?",e.getMessage(),id);}
    }
}
