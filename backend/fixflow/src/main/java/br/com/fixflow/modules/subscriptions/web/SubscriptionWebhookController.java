package br.com.fixflow.modules.subscriptions.web;

import br.com.fixflow.modules.subscriptions.application.Subscriptions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/webhooks/asaas/subscriptions")
public class SubscriptionWebhookController {
    private final Subscriptions subscriptions;
    public SubscriptionWebhookController(Subscriptions subscriptions) { this.subscriptions=subscriptions; }
    public record Payment(@NotBlank @Size(max=100) String id) { }
    public record Event(@NotBlank @Size(max=200) String id,@NotBlank @Size(max=100) String event,@Valid Payment payment) { }
    @PostMapping public void receive(@RequestHeader(value="asaas-access-token",required=false) String token,@Valid @RequestBody Event event) {
        subscriptions.webhook(token,event.id(),event.event(),event.payment()==null?null:event.payment().id());
    }
}
