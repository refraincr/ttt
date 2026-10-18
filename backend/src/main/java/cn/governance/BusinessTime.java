package cn.governance;
import java.time.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
@Configuration
public class BusinessTime {
    @Bean public Clock businessClock() { return Clock.system(ZoneId.of("Asia/Shanghai")); }
    public static boolean backlog(LocalDateTime created,LocalDateTime reviewed,String state,int days,Clock clock) {
        return "OPEN".equals(state) && reviewed==null && !LocalDateTime.now(clock).isBefore(created.plusDays(days));
    }
}
