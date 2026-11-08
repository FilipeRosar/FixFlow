package br.com.fixflow.modules.inventory.application;

import br.com.fixflow.modules.inventory.domain.Part;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface Parts {
    List<Part> list();
    List<Part> belowMinimum();
    record StockAlert(UUID id, UUID partId, String sku, String name, BigDecimal quantityOnHand,
                      BigDecimal minQuantity, java.time.Instant createdAt) { }
    List<StockAlert> alerts();
    record ReconciliationAlert(UUID id, UUID partId, String sku, String name, BigDecimal movementQuantity,
                               BigDecimal quantityOnHand, java.time.Instant createdAt, java.time.Instant lastCheckedAt) { }
    List<ReconciliationAlert> reconciliationAlerts();
    Part create(String sku, String name, String unit, BigDecimal cost, BigDecimal salePrice,
                BigDecimal minimum);
    Part get(UUID id);
    Part update(UUID id, String sku, String name, String unit, BigDecimal cost, BigDecimal salePrice, BigDecimal minimum, int version);
    Part receive(UUID id, BigDecimal quantity, BigDecimal unitCost, int version);
    Part adjust(UUID id, BigDecimal quantity, String reason, int version);
    void delete(UUID id, int version);
}
