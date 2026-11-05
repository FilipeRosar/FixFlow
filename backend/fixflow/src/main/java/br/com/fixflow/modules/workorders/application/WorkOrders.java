package br.com.fixflow.modules.workorders.application;

import br.com.fixflow.modules.workorders.domain.WorkOrder;
import java.util.List;
import java.util.UUID;

public interface WorkOrders {
    WorkOrder open(UUID customerId, UUID equipmentId, String reportedIssue, List<String> receivedAccessories, String physicalCondition);
    WorkOrder get(UUID id);
    record Page(List<WorkOrder> content, long totalElements, int page, int size) { }
    Page search(UUID technicianId, String status, boolean openOnly, int page, int size);
    record Technician(UUID userId, String fullName) { }
    List<Technician> technicians();
    WorkOrder assignTechnician(UUID id, UUID technicianId, int version);
    WorkOrder deliver(UUID id, int version, int warrantyDays, String reason, boolean paymentOverride);
    WorkOrder transition(UUID id, String toStatus, int version, String reason, boolean paymentOverride);
}
