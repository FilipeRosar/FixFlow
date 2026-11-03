package br.com.fixflow.shared.web.registration.controller;

import br.com.fixflow.shared.application.registration.usecase.PostalCodeLookup;
import br.com.fixflow.shared.application.registration.PostalCodeUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/enderecos/cep")
public class PostalCodeController {
    private final PostalCodeLookup lookup;

    public PostalCodeController(PostalCodeLookup lookup) { this.lookup = lookup; }

    @GetMapping("/{postalCode}")
    public ResponseEntity<?> lookup(@PathVariable String postalCode) {
        if (!postalCode.matches("[0-9]{8}")) return problem(HttpStatus.BAD_REQUEST, "Informe um CEP com 8 dígitos.");
        try {
            var address = lookup.lookup(postalCode);
            if (address.isEmpty()) return problem(HttpStatus.NOT_FOUND, "CEP não encontrado.");
            return ResponseEntity.ok(address.get());
        } catch (PostalCodeUnavailableException exception) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "Consulta de CEP indisponível. Preencha o endereço manualmente.");
        }
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}
