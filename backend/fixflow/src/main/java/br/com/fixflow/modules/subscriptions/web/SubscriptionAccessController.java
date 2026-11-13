package br.com.fixflow.modules.subscriptions.web;

import br.com.fixflow.modules.subscriptions.application.Subscriptions;
import br.com.fixflow.modules.subscriptions.domain.SubscriptionAccess;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/subscription/access")
public class SubscriptionAccessController {
    private final Subscriptions subscriptions;
    public SubscriptionAccessController(Subscriptions subscriptions) { this.subscriptions=subscriptions; }
    @GetMapping public SubscriptionAccess load() { return subscriptions.access(); }
}
