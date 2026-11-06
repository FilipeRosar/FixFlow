package br.com.fixflow.modules.quotes.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PublicQuotes {
    record Item(String type, String description, BigDecimal quantity, BigDecimal unitPrice) { }
    record Proposal(int version, String status, BigDecimal laborSubtotal, BigDecimal partsSubtotal,
                    BigDecimal discountAmount, BigDecimal total, Instant validUntil, String warrantyTerms, List<Item> items) { }
    record View(String companyName, long workOrderNumber, String equipmentLabel, String workOrderStatus, boolean readOnly, Proposal quote) { }
    Optional<View> findByHash(String tokenHash);
}
