package br.com.fixflow.modules.billing.web;

import br.com.fixflow.modules.billing.application.Payments;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@RestController
@Validated
@RequestMapping("/api/v1/work-orders/{order}/payments")
public class PaymentController {
    private final Payments payments;
    public PaymentController(Payments payments) { this.payments = payments; }
    public record Request(@NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount,
                          @NotNull @Pattern(regexp = "CASH|PIX|CREDIT_CARD|DEBIT_CARD|BANK_TRANSFER|OTHER") String method,
                          @NotNull Instant paidAt, @Size(max = 2000) String notes) { }
    @PostMapping public ResponseEntity<Payments.Payment> register(@PathVariable UUID order,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String key, @Valid @RequestBody Request request) {
        var result = payments.register(order, key, request.amount(), request.method(), request.paidAt().truncatedTo(ChronoUnit.MICROS),
                request.notes() == null ? null : request.notes().strip());
        return ResponseEntity.status(result.replayed() ? 200 : 201).body(result.payment());
    }
    @GetMapping public List<Payments.Payment> list(@PathVariable UUID order) { return payments.list(order); }
}
