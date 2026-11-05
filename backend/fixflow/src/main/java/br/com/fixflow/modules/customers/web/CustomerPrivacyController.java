package br.com.fixflow.modules.customers.web;

import br.com.fixflow.modules.customers.application.CustomerPrivacy;
import br.com.fixflow.modules.customers.domain.CustomerExport;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/customers/{customerId}")
public class CustomerPrivacyController {
    private final CustomerPrivacy privacy;
    public CustomerPrivacyController(CustomerPrivacy privacy) { this.privacy = privacy; }
    @GetMapping("/export") public ResponseEntity<CustomerExport> export(@PathVariable UUID customerId) {
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=cliente-" + customerId + ".json")
                .header("Cache-Control", "no-store").header("Pragma", "no-cache")
                .header("X-Content-Type-Options", "nosniff").body(privacy.export(customerId));
    }
    public record Confirmation(@NotNull @AssertTrue Boolean confirmed) { }
    @PostMapping("/anonymize") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void anonymize(@PathVariable UUID customerId, @Valid @RequestBody Confirmation confirmation) {
        privacy.anonymize(customerId);
    }
}
