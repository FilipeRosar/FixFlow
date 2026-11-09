package br.com.fixflow.modules.billing;

import br.com.fixflow.modules.billing.application.Finance;
import br.com.fixflow.modules.billing.web.FinanceController;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;


class FinanceControllerTest {
    private int calls;
    private Object[] arguments;
    private final Finance finance = (from, to, status, method, page) -> {
        calls++;
        arguments = new Object[]{from, to, status, method, page};
        return null;
    };
    private final FinanceController controller = new FinanceController(finance);
    private final Instant from = Instant.parse("2026-11-01T03:00:00Z");
    private final Instant to = Instant.parse("2026-11-10T03:00:00Z");

    @Test void forwardsExclusiveDateBoundsAndPagination() {
        controller.report(from, to, "CONFIRMED", "PIX", 2);
        assertEquals(1, calls);
        assertArrayEquals(new Object[]{from, to, "CONFIRMED", "PIX", 2}, arguments);
    }
    @Test void rejectsInvalidDatesFiltersAndPageBeforeQuerying() {
        assertThrows(ResponseStatusException.class, () -> controller.report(to, from, "", "", 0));
        assertThrows(ResponseStatusException.class, () -> controller.report(from, from.plusSeconds(368L * 86400), "", "", 0));
        assertThrows(ResponseStatusException.class, () -> controller.report(from, to, "PENDING", "", 0));
        assertThrows(ResponseStatusException.class, () -> controller.report(from, to, "", "UNKNOWN", 0));
        assertThrows(ResponseStatusException.class, () -> controller.report(from, to, "", "", -1));
        assertEquals(0, calls);
    }
}
