package br.com.fixflow.modules.workorders;

import br.com.fixflow.modules.workorders.domain.DeliveryWarranty;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class DeliveryWarrantyTest {
    @Test void usesSaoPauloCalendarRatherThanUtcAtMidnight() {
        assertEquals(LocalDate.of(2026, 11, 6), DeliveryWarranty.until(Instant.parse("2026-11-06T02:59:59Z"), 1));
        assertEquals(LocalDate.of(2026, 11, 7), DeliveryWarranty.until(Instant.parse("2026-11-06T03:00:00Z"), 1));
    }
    @Test void handlesLeapYearsAndMonthBoundaries() {
        assertEquals(LocalDate.of(2024, 3, 1), DeliveryWarranty.until(Instant.parse("2024-02-28T15:00:00Z"), 2));
        assertEquals(LocalDate.of(2026, 2, 1), DeliveryWarranty.until(Instant.parse("2026-01-31T15:00:00Z"), 1));
    }
    @Test void zeroMeansNoAdditionalDeadlineAndRejectsInvalidPeriods() {
        var now = Instant.parse("2026-11-05T12:00:00Z");
        assertNull(DeliveryWarranty.until(now, 0));
        assertThrows(IllegalArgumentException.class, () -> DeliveryWarranty.until(now, -1));
        assertThrows(IllegalArgumentException.class, () -> DeliveryWarranty.until(now, 3651));
    }
}
