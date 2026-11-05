package br.com.fixflow.modules.equipment.web;

import br.com.fixflow.modules.equipment.application.EquipmentManagement;
import br.com.fixflow.modules.equipment.domain.Equipment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import java.net.URI;
import java.util.UUID;

@RestController
@Validated
@RequestMapping("/api/v1")
public class EquipmentController {
    private final EquipmentManagement equipment;
    public EquipmentController(EquipmentManagement equipment) { this.equipment = equipment; }
    @GetMapping("/equipment") public EquipmentManagement.Page search(
            @RequestParam(defaultValue = "") @Size(max = 150) String serial,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return equipment.search(serial, customerId, page, size);
    }
    @GetMapping("/customers/{customerId}/equipment") public EquipmentManagement.Page byCustomer(@PathVariable UUID customerId,
            @RequestParam(defaultValue = "") @Size(max = 150) String serial,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return equipment.search(serial, customerId, page, size);
    }
    @GetMapping("/equipment/{id}") public Equipment get(@PathVariable UUID id) { return equipment.get(id); }
    @PostMapping("/equipment") public ResponseEntity<Equipment> create(@Valid @RequestBody EquipmentRequest request) {
        Equipment result = equipment.create(request.equipment());
        return ResponseEntity.created(URI.create("/api/v1/equipment/" + result.id())).body(result);
    }
    @PutMapping("/equipment/{id}") public Equipment update(@PathVariable UUID id, @Valid @RequestBody EquipmentRequest request) {
        return equipment.update(id, request.equipment());
    }
    public record DescriptionRequest(@NotNull @Size(max = 2000) String description) { }
    @PatchMapping("/equipment/{id}/description") public Equipment describe(@PathVariable UUID id, @Valid @RequestBody DescriptionRequest request) {
        return equipment.describe(id, request.description().strip());
    }
    @DeleteMapping("/equipment/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) { equipment.delete(id); }
}
