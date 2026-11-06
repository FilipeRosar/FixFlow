package br.com.fixflow.modules.quotes.web;

import br.com.fixflow.modules.quotes.application.PublicQuotes;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/public/v1/work-orders")
public class PublicQuoteController {
    private final PublicQuotes quotes;
    public PublicQuoteController(PublicQuotes quotes) { this.quotes = quotes; }
    @GetMapping("/{token}")
    public ResponseEntity<?> get(@RequestAttribute("publicQuoteHash") String hash) {
        var result = quotes.findByHash(hash);
        if (result.isPresent()) return ResponseEntity.ok(result.get());
        // Fixed representation: no path, token, IDs, or distinct reason for expiration/revocation.
        return ResponseEntity.status(404).body(new Missing(404, "Orçamento não encontrado ou indisponível."));
    }
    private record Missing(int status, String detail) { }
}
