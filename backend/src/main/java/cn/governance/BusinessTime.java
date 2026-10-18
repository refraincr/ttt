package cn.governance;
import java.time.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
@Configuration
public class BusinessTime {
    @Bean public Clock businessClock() { return Clock.system(ZoneId.of("Asia/Shanghai")); }
    public static boolean departmentOverdue(String state,LocalDateTime deadline,Clock clock){return java.util.Set.of("ASSIGNED","RETURNED").contains(state) && !LocalDateTime.now(clock).isBefore(deadline);}
    public static long reviewWait(String state,LocalDateTime submitted,Clock clock){return "SUBMITTED".equals(state)&&submitted!=null?Math.max(0,Duration.between(submitted,LocalDateTime.now(clock)).getSeconds()):0;}
    public static boolean backlog(LocalDateTime created,LocalDateTime reviewed,String state,int days,Clock clock) {
        return "OPEN".equals(state) && reviewed==null && !LocalDateTime.now(clock).isBefore(created.plusDays(days));
    }
}
