package br.com.fixflow.modules.inventory.application;

import br.com.fixflow.modules.inventory.domain.Part;
import java.math.BigDecimal;
import java.util.UUID;

/** Joins the authorized caller transaction; never opens an independent tenant transaction. */
public interface StockOperations {
    Part consume(UUID tenant, UUID actor, UUID part, UUID order, UUID item, BigDecimal quantity);
    void restore(UUID tenant, UUID actor, UUID part, UUID order, UUID item, BigDecimal quantity, BigDecimal cost);
}
