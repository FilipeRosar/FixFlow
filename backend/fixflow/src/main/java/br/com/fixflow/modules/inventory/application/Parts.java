package br.com.fixflow.modules.inventory.application;

import br.com.fixflow.modules.inventory.domain.Part;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface Parts {
    List<Part> list();
    Part create(String sku, String name, String unit, BigDecimal cost, BigDecimal salePrice,
                BigDecimal minimum);
    Part get(UUID id);
    Part update(UUID id, String sku, String name, String unit, BigDecimal cost, BigDecimal salePrice, BigDecimal minimum, int version);
    void delete(UUID id, int version);
}
