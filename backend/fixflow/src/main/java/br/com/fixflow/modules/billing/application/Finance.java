package br.com.fixflow.modules.billing.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface Finance {
    public record Entry(UUID id, UUID workOrderId, long number, String customerName,
            BigDecimal amount, String method, String status, Instant paidAt) { }
    public record Summary(BigDecimal received, BigDecimal voided, long confirmedCount) { }
    public record Report(Summary summary, List<Entry> content, long totalElements) { }
    Report report(Instant from, Instant to, String status, String method, int page);
}
