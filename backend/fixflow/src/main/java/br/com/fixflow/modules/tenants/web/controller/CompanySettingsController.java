package br.com.fixflow.modules.tenants.web.controller;
import br.com.fixflow.modules.tenants.application.CompanyConfiguration;
import br.com.fixflow.modules.tenants.domain.CompanySettings;
import br.com.fixflow.modules.tenants.web.dto.CompanySettingsRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/tenant")
public class CompanySettingsController {
    private final CompanyConfiguration configuration;
    public CompanySettingsController(CompanyConfiguration configuration) { this.configuration = configuration; }
    @GetMapping public CompanySettings get() { return configuration.load(); }
    @PutMapping public CompanySettings put(@Valid @RequestBody CompanySettingsRequest request) {
        return configuration.save(new CompanySettings(request.logoDataUrl(), request.warrantyTerms(), request.quoteValidityDays(), request.allowNegativeStock()));
    }
}
