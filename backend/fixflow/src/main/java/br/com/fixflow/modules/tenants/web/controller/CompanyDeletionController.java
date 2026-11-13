package br.com.fixflow.modules.tenants.web.controller;

import br.com.fixflow.modules.tenants.application.CompanyDeletion;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/v1/tenant")
public class CompanyDeletionController {
    private final CompanyDeletion deletion;
    public CompanyDeletionController(CompanyDeletion deletion) { this.deletion=deletion; }
    public record Request(@NotBlank @Size(max=72) String password,
            @NotBlank @Pattern(regexp="EXCLUIR") String confirmation) {
        @Override public String toString() { return "CompanyDeletionRequest[redacted]"; }
    }
    @DeleteMapping @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@Valid @RequestBody Request request) { deletion.delete(request.password()); }
}
