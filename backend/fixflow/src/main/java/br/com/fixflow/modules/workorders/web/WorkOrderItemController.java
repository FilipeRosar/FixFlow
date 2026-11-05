package br.com.fixflow.modules.workorders.web;
import br.com.fixflow.modules.workorders.application.WorkOrderItems;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.math.BigDecimal;
import java.net.URI;
import java.util.UUID;
@RestController
@RequestMapping("/api/v1/work-orders/{orderId}/items")
public class WorkOrderItemController {
    private final WorkOrderItems items;
    public WorkOrderItemController(WorkOrderItems items) { this.items = items; }
    public record Request(@NotNull UUID id, @NotNull @Min(0) Integer version,
                          @NotNull @Pattern(regexp = "SERVICE|PART") String type, UUID partId,
                          @NotBlank @Size(max = 500) String description,
                          @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 11, fraction = 3) BigDecimal quantity,
                          @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal unitPrice) { }
    @GetMapping public WorkOrderItems.Saved list(@PathVariable UUID orderId) { return items.list(orderId); }
    @PostMapping public ResponseEntity<WorkOrderItems.Saved> add(@PathVariable UUID orderId, @Valid @RequestBody Request request) {
        var saved = items.add(orderId, request.version(), new WorkOrderItems.Add(request.id(), request.type(), request.partId(),
                request.description().strip(), request.quantity(), request.unitPrice()));
        return ResponseEntity.created(URI.create("/api/v1/work-orders/" + orderId + "/items/" + request.id())).body(saved);
    }
    @DeleteMapping("/{itemId}") public WorkOrderItems.Saved remove(@PathVariable UUID orderId, @PathVariable UUID itemId, @RequestParam @Min(0) int version) {
        return items.remove(orderId, itemId, version);
    }
}
