package br.com.fixflow.modules.billing.web;

import br.com.fixflow.modules.billing.application.PixCharges;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/webhooks/asaas")
public class AsaasWebhookController {
    private final PixCharges charges;
    public AsaasWebhookController(PixCharges charges) { this.charges = charges; }
    public record Payment(@NotBlank @Size(max = 100) String id) { }
    public record Event(@NotBlank @Size(max = 200) String id, @NotBlank @Size(max = 100) String event, @Valid Payment payment) { }
    @PostMapping public void receive(@RequestHeader(value = "asaas-access-token", required = false) String token, @Valid @RequestBody Event event) {
        charges.webhook(token, event.id(), event.event(), event.payment() == null ? null : event.payment().id());
    }
}
