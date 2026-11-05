package br.com.fixflow.modules.inventory.domain;

import java.math.BigDecimal;
import java.util.UUID;

public record Part(UUID id, String sku, String name, String unit, BigDecimal cost, BigDecimal salePrice,
                   BigDecimal quantityOnHand, BigDecimal minQuantity, boolean active, int version) { }
