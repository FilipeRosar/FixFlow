package br.com.fixflow.modules.inventory.application;

import br.com.fixflow.modules.inventory.domain.Part;
import java.math.BigDecimal;
import java.util.List;

public interface Parts {
    List<Part> list();
    Part create(String sku, String name, String unit, BigDecimal cost, BigDecimal salePrice,
                BigDecimal quantity, BigDecimal minimum);
}
