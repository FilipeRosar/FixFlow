package br.com.fixflow.modules.billing.web;

import br.com.fixflow.modules.billing.application.Payments;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentVoidController {
    private final Payments payments;
    public PaymentVoidController(Payments payments) { this.payments = payments; }
    public record VoidRequest(@NotBlank @Size(max = 2000) String reason) { }
    @PostMapping("/{id}/void") public Payments.Payment voidPayment(@PathVariable UUID id, @Valid @RequestBody VoidRequest request) {
        return payments.voidPayment(id, request.reason());
    }
}
