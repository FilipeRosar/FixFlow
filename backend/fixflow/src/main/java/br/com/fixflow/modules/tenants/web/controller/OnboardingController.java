package br.com.fixflow.modules.tenants.web.controller;

import br.com.fixflow.modules.tenants.application.Onboarding;
import br.com.fixflow.shared.web.registration.dto.RegistrationRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ResponseEntity;

@RestController
@PreAuthorize("hasAuthority('tenant:manage')")
public class OnboardingController {
    private final Onboarding onboarding;
    public OnboardingController(Onboarding onboarding){this.onboarding=onboarding;}
    public record Profile(@NotBlank @Size(max=150) String companyName,@NotBlank @Pattern(regexp="[0-9]{11}|[0-9]{14}") String document,
        @NotBlank @Pattern(regexp="[0-9]{10,11}") String phone,@NotNull @Valid RegistrationRequest.Address address){ }
    @GetMapping("/api/v1/onboarding") public Onboarding.View load(){return onboarding.load();}
    @PutMapping("/api/v1/onboarding") public Onboarding.View save(@Valid @RequestBody Profile p){
        return onboarding.save(new Onboarding.Profile(p.companyName().strip(),p.document(),p.phone(),p.address().toApplication()));
    }
    @PostMapping("/api/v1/onboarding/complete") public Onboarding.View complete(){return onboarding.complete();}
    @GetMapping("/api/v1/tenant/export") public ResponseEntity<byte[]> export(){
        return ResponseEntity.ok().header("Content-Type","application/zip").header("Cache-Control","no-store")
            .header("Content-Disposition","attachment; filename=fixflow-dados-csv.zip").header("X-Content-Type-Options","nosniff").body(onboarding.exportCsv());
    }
}
