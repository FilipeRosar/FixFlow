package br.com.fixflow.modules.quotes.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Quote(UUID id, UUID workOrderId, int version, String status, List<Item> items,
                    String discountType, BigDecimal discountValue, BigDecimal laborSubtotal,
                    BigDecimal partsSubtotal, BigDecimal discountAmount, BigDecimal total,
                    Instant validUntil, String warrantyTerms, String logoDataUrl, Instant createdAt, Instant sentAt) {
    public record Item(String type, UUID partId, String description, BigDecimal quantity, BigDecimal unitPrice) { }
}
