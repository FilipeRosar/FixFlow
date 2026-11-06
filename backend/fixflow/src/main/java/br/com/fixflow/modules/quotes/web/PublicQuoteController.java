package br.com.fixflow.modules.quotes.web;

import br.com.fixflow.modules.quotes.application.PublicQuotes;
import org.springframework.web.bind.annotation.*;
import br.com.fixflow.modules.quotes.application.PublicQuoteDecisions;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/public/v1/work-orders")
public class PublicQuoteController {
    private final PublicQuotes quotes;
    private final PublicQuoteDecisions decisions;
    public PublicQuoteController(PublicQuotes quotes, PublicQuoteDecisions decisions) { this.quotes = quotes; this.decisions = decisions; }
    @GetMapping("/{token}")
    public ResponseEntity<?> get(@RequestAttribute("publicQuoteHash") String hash) {
        var result = quotes.findByHash(hash);
        if (result.isPresent()) return ResponseEntity.ok(result.get());
        // Fixed representation: no path, token, IDs, or distinct reason for expiration/revocation.
        return ResponseEntity.status(404).body(new Missing(404, "Orçamento não encontrado ou indisponível."));
    }
    public record DecisionRequest(Boolean confirmed, String contentHash, String reason) { }
    @PostMapping(value = "/{token}/approve", consumes = "application/json")
    public ResponseEntity<?> approve(@RequestAttribute("publicQuoteHash") String hash,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) DecisionRequest request, HttpServletRequest http) {
        return decide(hash, "APPROVED", key, request, http);
    }
    @PostMapping(value = "/{token}/reject", consumes = "application/json")
    public ResponseEntity<?> reject(@RequestAttribute("publicQuoteHash") String hash,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) DecisionRequest request, HttpServletRequest http) {
        return decide(hash, "REJECTED", key, request, http);
    }
    private ResponseEntity<?> decide(String hash, String choice, String key, DecisionRequest request, HttpServletRequest http) {
        if (request == null || !Boolean.TRUE.equals(request.confirmed()) || request.contentHash() == null || !request.contentHash().matches("[0-9a-f]{64}") ||
                key == null || !key.matches("[A-Za-z0-9._:-]{8,128}") || request.reason() != null && request.reason().length() > 2000)
            return ResponseEntity.badRequest().body(new Missing(400, "Confirme a decisão, o conteúdo da proposta e uma Idempotency-Key válida."));
        String reason = choice.equals("REJECTED") && request.reason() != null && !request.reason().isBlank() ? request.reason().strip() : null;
        String userAgent = http.getHeader("User-Agent");
        if (userAgent == null) userAgent = "";
        userAgent = userAgent.substring(0, Math.min(1024, userAgent.length()));
        var result = decisions.decide(hash, choice, request.contentHash(), reason, sha256(key), http.getRemoteAddr(), userAgent);
        if (result.status() == 200) return ResponseEntity.ok(result.receipt());
        if (result.status() == 404) return ResponseEntity.status(404).body(new Missing(404, "Orçamento não encontrado ou indisponível."));
        if (result.status() == 400) return ResponseEntity.badRequest().body(new Missing(400, "Dados da decisão inválidos."));
        String detail = switch (result.code() == null ? "" : result.code()) {
            case "QUOTE_CONTENT_CHANGED" -> "A proposta mudou. Recarregue e confira o conteúdo antes de decidir.";
            case "IDEMPOTENCY_KEY_REUSED" -> "Idempotency-Key já utilizada com outra decisão.";
            case "QUOTE_ALREADY_DECIDED" -> "Esta proposta já recebeu uma decisão.";
            default -> "Esta proposta não está disponível para decisão.";
        };
        return ResponseEntity.status(409).body(new Conflict(409, result.code(), detail));
    }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<?> invalidBody() { return ResponseEntity.badRequest().body(new Missing(400, "Corpo da decisão inválido.")); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unexpectedFailure() {
        return ResponseEntity.internalServerError().body(new Missing(500, "Não foi possível confirmar a operação. Tente novamente."));
    }
    private record Conflict(int status, String code, String detail) { }
    private record Missing(int status, String detail) { }
}
