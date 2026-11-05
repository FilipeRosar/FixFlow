package br.com.fixflow.modules.workorders.application;
import br.com.fixflow.modules.workorders.domain.WorkOrderItem;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
public interface WorkOrderItems {
    record Add(UUID id, String type, UUID partId, String description, BigDecimal quantity, BigDecimal unitPrice) { }
    record Saved(List<WorkOrderItem> items, int workOrderVersion) { }
    Saved list(UUID order);
    Saved add(UUID order, int version, Add item);
    Saved remove(UUID order, UUID item, int version);
}
