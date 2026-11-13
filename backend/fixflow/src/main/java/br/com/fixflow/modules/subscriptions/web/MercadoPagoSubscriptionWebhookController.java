package br.com.fixflow.modules.subscriptions.web;

import br.com.fixflow.modules.subscriptions.application.Subscriptions;
import br.com.fixflow.modules.subscriptions.application.MercadoPagoNotifications;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/webhooks/mercadopago/subscriptions")
@ConditionalOnProperty(name = "fixflow.subscriptions.provider", havingValue = "mercadopago", matchIfMissing = true)
public class MercadoPagoSubscriptionWebhookController {
    private final MercadoPagoNotifications gateway;
    private final Subscriptions subscriptions;
    public MercadoPagoSubscriptionWebhookController(MercadoPagoNotifications gateway, Subscriptions subscriptions) {
        this.gateway = gateway;
        this.subscriptions = subscriptions;
    }
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void receive(@RequestHeader(value = "x-signature", required = false) String signature,
                        @RequestHeader(value = "x-request-id", required = false) String requestId,
                        @RequestParam(value = "data.id", required = false) String resourceId,
                        @RequestBody JsonNode body) {
        gateway.authenticateNotification(signature, requestId, resourceId);
        if (!resourceId.equalsIgnoreCase(body.path("data").path("id").asText("")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Referência do evento divergente.");
        subscriptions.mercadoPagoNotification(body.path("type").asText(""), resourceId);
    }
}
