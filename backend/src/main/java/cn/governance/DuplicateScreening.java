package cn.governance;

import java.util.*;
import java.time.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;

@Service
public class DuplicateScreening {
    private final JdbcTemplate jdbc;
    public DuplicateScreening(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Settings(int windowDays, double threshold, long version) {}
    public record Candidate(long id, String description, LocalDateTime occurredAt, long secondsApart, double similarity, String basis) {}
    public Settings settings() { return jdbc.queryForObject("SELECT * FROM tb_duplicate_settings WHERE id=1", (r,n)->new Settings(r.getInt("window_days"),r.getDouble("threshold"),r.getLong("version"))); }
    private Set<String> grams(String text) {
        String s = IdentityValues.clean(text).replaceAll("[\\p{P}\\p{Z}\\s]", "").toLowerCase(Locale.ROOT);
        Set<String> result = new HashSet<>();
        if(s.length()==1) result.add(s);
        for(int i=0;i<s.length()-1;i++) result.add(s.substring(i,i+2));
        return result;
    }
    private double similarity(String a, String b) {
        var x=grams(a); var y=grams(b);
        if(x.isEmpty() || y.isEmpty()) return 0;
        long common=x.stream().filter(y::contains).count();
        return 2.0*common/(x.size()+y.size());
    }
    public List<Candidate> candidates(long id) {
        var current=jdbc.queryForMap("SELECT * FROM tb_input_record WHERE id=? AND is_deleted=0", id);
        if(!"CONFIRMED".equals(current.get("identity_review_state"))) return List.of();
        var time=jdbc.queryForObject("SELECT occurred_at FROM tb_input_record WHERE id=?",(r,n)->r.getObject(1,LocalDateTime.class),id);
        var config=settings();
        return jdbc.query("SELECT id, problem_description, occurred_at FROM tb_input_record WHERE matched_person_id=? AND identity_review_state='CONFIRMED' AND is_deleted=0 AND id<>? AND occurred_at BETWEEN ? AND ? ORDER BY occurred_at,id",
            (r,n)-> {
                var other=r.getObject("occurred_at",LocalDateTime.class);
                return new Candidate(r.getLong("id"),r.getString("problem_description"),other,Math.abs(Duration.between(time,other).getSeconds()),similarity((String)current.get("problem_description"),r.getString("problem_description")),"描述字符二元组 Dice 相似度；时间差包含窗口边界");
            },current.get("matched_person_id"),id,time.minusDays(config.windowDays()),time.plusDays(config.windowDays())).stream().filter(c->c.similarity()>=config.threshold()).toList();
    }
    public void screen(long id) {
        // 重复审核在任务08实现；此阶段不生成有效事件或有效次数。
        String state=candidates(id).isEmpty()?"SCREENED":"SUSPECTED";
        jdbc.update("UPDATE tb_input_record SET duplicate_review_state=? WHERE id=?",state,id);
    }
}

