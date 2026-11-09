package br.com.fixflow.modules.billing.web;

import br.com.fixflow.modules.billing.application.PixCharges;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@Validated
@RequestMapping("/api/v1/work-orders/{order}/pix-charges")
public class PixChargeController {
    private final PixCharges charges;
    public PixChargeController(PixCharges charges) { this.charges = charges; }
    public record Request(@NotBlank @Pattern(regexp = "cus_[a-zA-Z0-9]+") String asaasCustomerId,
                          @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount,
                          @NotNull LocalDate dueDate) { }
    @PostMapping public PixCharges.Charge create(@PathVariable UUID order, @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String key,
                                                @Valid @RequestBody Request request) {
        return charges.create(order, key, request.asaasCustomerId(), request.amount(), request.dueDate());
    }
    @PostMapping("/{id}/refresh") public PixCharges.Charge refresh(@PathVariable UUID order, @PathVariable UUID id) { return charges.refresh(order, id); }
    @GetMapping public List<PixCharges.Charge> list(@PathVariable UUID order) { return charges.list(order); }
}
