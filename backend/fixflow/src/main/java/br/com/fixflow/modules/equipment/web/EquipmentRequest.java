package br.com.fixflow.modules.equipment.web;

import br.com.fixflow.modules.equipment.domain.Equipment;
import jakarta.validation.constraints.*;
import java.util.UUID;

public record EquipmentRequest(@NotNull UUID customerId,
        @NotNull @Pattern(regexp = "CELULAR|NOTEBOOK|DESKTOP|IMPRESSORA|PERIFERICO|OUTRO") String category,
        @Size(max = 100) String brand, @NotBlank @Size(max = 150) String model,
        @Size(max = 150) String serialNumber, @Size(max = 2000) String description) {
    public Equipment equipment() {
        return new Equipment(null, customerId, null, false, category, clean(brand), model.strip(), clean(serialNumber), clean(description));
    }
    private String clean(String value) { return value == null ? "" : value.strip(); }
}
