package br.com.fixflow.modules.subscriptions.web;

import br.com.fixflow.modules.subscriptions.application.Subscriptions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/v1/subscription")
@PreAuthorize("hasAuthority('tenant:manage')")
public class SubscriptionController {
    private final Subscriptions subscriptions;
    public SubscriptionController(Subscriptions subscriptions) { this.subscriptions=subscriptions; }
    public record Request(@NotBlank @Pattern(regexp="STARTER|PROFESSIONAL|BUSINESS") String plan) { }
    @GetMapping public Subscriptions.View load() { return subscriptions.load(); }
    @PostMapping public Subscriptions.View subscribe(@RequestHeader("Idempotency-Key") @Pattern(regexp="[a-zA-Z0-9_-]{16,100}") String key,
            @Valid @RequestBody Request request) { return subscriptions.subscribe(request.plan(),key); }
    @PostMapping("/plan") public Subscriptions.View changeTrialPlan(@Valid @RequestBody Request request) { return subscriptions.changeTrialPlan(request.plan()); }
    @PostMapping("/refresh") public Subscriptions.View refresh() { return subscriptions.refresh(); }
    @PostMapping("/cancel") public Subscriptions.View cancel() { return subscriptions.cancel(); }
}
