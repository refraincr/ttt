package cn.governance;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;
class BusinessTimeTest {
    private final LocalDateTime created=LocalDateTime.of(2026,10,1,12,0);
    private Clock at(String instant) {return Clock.fixed(Instant.parse(instant),ZoneId.of("Asia/Shanghai"));}
    @Test void taskDeadlineAndReviewStagesAreIndependent(){
        Clock now=at("2026-10-01T04:00:00Z");
        assertTrue(BusinessTime.departmentOverdue("ASSIGNED",created,now));
        assertTrue(BusinessTime.departmentOverdue("RETURNED",created,now));
        assertFalse(BusinessTime.departmentOverdue("ASSIGNED",created.plusSeconds(1),now));
        assertFalse(BusinessTime.departmentOverdue("SUBMITTED",created.minusDays(1),now));
        assertFalse(BusinessTime.departmentOverdue("CLOSED",created.minusDays(1),now));
        assertEquals(60,BusinessTime.reviewWait("SUBMITTED",created.minusMinutes(1),now));
        assertEquals(0,BusinessTime.reviewWait("RETURNED",created.minusMinutes(1),now));
    }
    @Test void sevenDayBoundaryUsesBusinessClock() {
        assertFalse(BusinessTime.backlog(created,null,"OPEN",7,at("2026-10-08T03:59:59Z")));
        assertTrue(BusinessTime.backlog(created,null,"OPEN",7,at("2026-10-08T04:00:00Z")));
        assertTrue(BusinessTime.backlog(created,null,"OPEN",7,at("2026-10-10T04:00:00Z")));
    }
    @Test void reviewedAndClosedAlertsNeverBecomeBacklog() {
        assertFalse(BusinessTime.backlog(created,created.plusDays(1),"OPEN",7,at("2026-11-01T00:00:00Z")));
        assertFalse(BusinessTime.backlog(created,null,"CLOSED",7,at("2026-11-01T00:00:00Z")));
    }
    @Test void configurableThresholdAndRepeatedReadsAreStable() {
        Clock now=at("2026-10-03T04:00:00Z");
        assertFalse(BusinessTime.backlog(created,null,"OPEN",3,now));
        assertTrue(BusinessTime.backlog(created,null,"OPEN",2,now));
        assertTrue(BusinessTime.backlog(created,null,"OPEN",2,now));
    }
}
