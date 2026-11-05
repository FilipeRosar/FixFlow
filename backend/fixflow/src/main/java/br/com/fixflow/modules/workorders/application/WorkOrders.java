package br.com.fixflow.modules.workorders.application;

import br.com.fixflow.modules.workorders.domain.WorkOrder;
import java.util.List;
import java.util.UUID;

public interface WorkOrders {
    WorkOrder open(UUID customerId, UUID equipmentId, String reportedIssue, List<String> receivedAccessories, String physicalCondition);
    WorkOrder get(UUID id);
}
