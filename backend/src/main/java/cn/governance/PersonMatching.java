package cn.governance;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PersonMatching {
    private final JdbcTemplate jdbc;
    private final DuplicateScreening duplicates;
    public PersonMatching(JdbcTemplate jdbc, DuplicateScreening duplicates) { this.jdbc=jdbc; this.duplicates=duplicates; }
    public record Person(long id,String name,String idCard,String phone) {}
    private static final org.springframework.jdbc.core.RowMapper<Person> PERSON_MAPPER =
        (r,n)->new Person(r.getLong("id"),r.getString("name"),r.getString("id_card"),r.getString("phone"));
    public List<Person> people(String q) {
        InputChecks.require(q!=null && q.length()<=100,"搜索过长");
        return jdbc.query("SELECT * FROM tb_person WHERE LOCATE(?,name)>0 OR LOCATE(?,COALESCE(id_card,''))>0 OR LOCATE(?,phone)>0 ORDER BY id LIMIT 100",PERSON_MAPPER,q,q,q);
    }
    public Person person(long id) {
        return jdbc.query("SELECT * FROM tb_person WHERE id=?",PERSON_MAPPER,id).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    private List<Person> conflicts(String name,String card,String phone) {
        return jdbc.query("SELECT * FROM tb_person WHERE (?<>'' AND id_card=?) OR (?=1 AND phone=?)", PERSON_MAPPER,card,card,IdentityValues.phone(phone),phone);
    }
    public void automatic(long recordId,String name,String card,String phone) {
        var candidates=conflicts(name,card,phone);
        Person match=null;
        if(!card.isEmpty() && !name.isEmpty()) {
            var cardMatches=candidates.stream().filter(p->card.equals(p.idCard())).toList();
            if(cardMatches.size()==1 && cardMatches.getFirst().name().equals(name)) match=cardMatches.getFirst();
            else if(cardMatches.isEmpty() && candidates.isEmpty()) match=create(name,card,phone);
        } else if(!name.isEmpty() && IdentityValues.phone(phone) && candidates.size()==1 && candidates.getFirst().name().equals(name)) match=candidates.getFirst();
        if(match!=null) confirm(recordId,match.id());
    }
    private Person create(String name,String card,String phone) {
        long id=JdbcIds.insert(jdbc,"INSERT INTO tb_person(name,id_card,phone) VALUES (?,?,?)",name,card.isEmpty()?null:card,phone);
        return person(id);
    }
    public long review(long recordId,Long personId,String name,String card,String phone) {
        InputChecks.require(!name.isEmpty() && ((!card.isEmpty() && IdentityValues.card(card)) || IdentityValues.phone(phone)),"身份不足：需姓名和有效身份证或手机号");
        InputChecks.require(card.isEmpty() || IdentityValues.card(card),"身份证无效");
        var conflicts=conflicts(name,card,phone);
        Person selected;
        if(personId==null) {
            InputChecks.require(conflicts.isEmpty(),"存在未解决身份冲突，不能新建档案");
            selected=create(name,card,phone);
        } else {
            selected=person(personId);
            InputChecks.require(selected.name().equals(name),"姓名与档案冲突");
            InputChecks.require(card.isEmpty()?phone.equals(selected.phone()):card.equals(selected.idCard()),"身份依据与档案不一致");
            InputChecks.require(conflicts.stream().allMatch(p->p.id()==personId),"存在其他档案冲突");
        }
        jdbc.update("UPDATE tb_input_record SET party_name=?,party_id_card=?,party_phone=? WHERE id=?",name,card,phone,recordId);
        confirm(recordId,selected.id());
        return selected.id();
    }
    private void confirm(long recordId,long personId) {
        jdbc.update("UPDATE tb_input_record SET matched_person_id=?, matched_at=CURRENT_TIMESTAMP, match_status=1,identity_review_state='CONFIRMED',version=version+1 WHERE id=?",personId,recordId);
        jdbc.queryForList("SELECT id FROM tb_input_record WHERE matched_person_id=? AND identity_review_state='CONFIRMED' AND is_deleted=0",Long.class,personId).forEach(duplicates::screen);
    }
}
