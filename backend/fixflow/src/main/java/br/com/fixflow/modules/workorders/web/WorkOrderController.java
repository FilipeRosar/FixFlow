package br.com.fixflow.modules.workorders.web;

import br.com.fixflow.modules.workorders.application.WorkOrders;
import br.com.fixflow.modules.workorders.domain.WorkOrder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/work-orders")
public class WorkOrderController {
    private final WorkOrders orders;
    public WorkOrderController(WorkOrders orders) { this.orders = orders; }
    public record OpenRequest(@NotNull UUID customerId, @NotNull UUID equipmentId,
                              @NotBlank @Size(max = 4000) String reportedIssue,
                              @NotNull @Size(max = 50) List<@NotBlank @Size(max = 150) String> receivedAccessories,
                              @NotBlank @Size(max = 2000) String physicalCondition) { }
    @PostMapping public ResponseEntity<WorkOrder> open(@Valid @RequestBody OpenRequest request) {
        var order = orders.open(request.customerId(), request.equipmentId(), request.reportedIssue().strip(),
                request.receivedAccessories().stream().map(String::strip).toList(), request.physicalCondition().strip());
        return ResponseEntity.created(URI.create("/api/v1/work-orders/" + order.id())).body(order);
    }
    public record TransitionRequest(@NotBlank @Size(max = 30) String toStatus,
                                    @NotNull @Min(0) Integer version, @Size(max = 2000) String reason,
                                    Boolean paymentOverride) { }
    @PatchMapping("/{id}/status") public WorkOrder transition(@PathVariable UUID id, @Valid @RequestBody TransitionRequest request) {
        return orders.transition(id, request.toStatus(), request.version(),
                request.reason() == null ? null : request.reason().strip(), Boolean.TRUE.equals(request.paymentOverride()));
    }
    @GetMapping("/technicians") public List<WorkOrders.Technician> technicians() { return orders.technicians(); }
    public record AssignTechnicianRequest(@NotNull UUID technicianId, @NotNull @Min(0) Integer version) { }
    @PatchMapping("/{id}/technician") public WorkOrder assignTechnician(@PathVariable UUID id, @Valid @RequestBody AssignTechnicianRequest request) {
        return orders.assignTechnician(id, request.technicianId(), request.version());
    }
    @GetMapping("/{id}") public WorkOrder get(@PathVariable UUID id) { return orders.get(id); }
}
