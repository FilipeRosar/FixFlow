package br.com.fixflow.modules.workorders.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Trusted server-side facts. Diagnosis is derived from registered history, never from request flags. */
public record WorkOrderFacts(UUID technicianId, String diagnosis, UUID quoteId, String quoteStatus,
                             int quoteItemCount, BigDecimal quoteTotal, Instant quoteValidUntil,
                             int serviceCount, boolean partsConsumed, boolean partsWrittenOff,
                             boolean paymentSettled, boolean diagnosticFeeRegistered,
                             String missingPart, boolean maintenanceStarted, int warrantyDays) {
    public static WorkOrderFacts empty() {
        return new WorkOrderFacts(null, null, null, "DRAFT", 0, BigDecimal.ZERO, null,
                0, false, false, false, false, null, false, 0);
    }
    public boolean currentQuote(Instant now) {
        return quoteId != null && quoteValidUntil != null && quoteValidUntil.isAfter(now);
    }
}
