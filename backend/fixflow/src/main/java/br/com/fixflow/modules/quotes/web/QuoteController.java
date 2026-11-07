package br.com.fixflow.modules.quotes.web;

import br.com.fixflow.modules.quotes.application.Quotes;
import br.com.fixflow.modules.quotes.domain.Quote;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/work-orders/{orderId}/quotes")
public class QuoteController {
    private final Quotes quotes;
    private final br.com.fixflow.modules.quotes.application.QuoteSending sending;
    private final br.com.fixflow.modules.quotes.application.QuoteLinks links;
    public QuoteController(Quotes quotes, br.com.fixflow.modules.quotes.application.QuoteSending sending, br.com.fixflow.modules.quotes.application.QuoteLinks links) { this.quotes = quotes; this.sending = sending; this.links = links; }
    public record ItemRequest(@NotNull @Pattern(regexp = "SERVICE|PART") String type, UUID partId,
                              @NotBlank @Size(max = 500) String description,
                              @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 11, fraction = 3) BigDecimal quantity,
                              @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal unitPrice) { }
    public record Request(@NotNull @Min(0) Integer workOrderVersion,
                          @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid ItemRequest> items,
                          @NotNull @Pattern(regexp = "FIXED|PERCENT") String discountType,
                          @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal discountValue) {
        Quotes.Draft draft() {
            return new Quotes.Draft(workOrderVersion, items.stream().map(item -> new Quote.Item(item.type(), item.partId(),
                    item.description().strip(), item.quantity(), item.unitPrice())).toList(), discountType, discountValue);
        }
    }
    @GetMapping public Quotes.History list(@PathVariable UUID orderId) { return quotes.list(orderId); }
    @PostMapping public ResponseEntity<Quotes.History> create(@PathVariable UUID orderId, @Valid @RequestBody Request request) {
        var result = quotes.create(orderId, request.draft());
        return ResponseEntity.created(URI.create("/api/v1/work-orders/" + orderId + "/quotes/" + result.quotes().getFirst().id())).body(result);
    }
    @PutMapping("/{quoteId}") public Quotes.History update(@PathVariable UUID orderId, @PathVariable UUID quoteId, @Valid @RequestBody Request request) {
        return quotes.update(orderId, quoteId, request.draft());
    }
    public record SendRequest(@NotNull @Min(0) Integer workOrderVersion) { }
    @PostMapping("/{quoteId}/send")
    public ResponseEntity<br.com.fixflow.modules.quotes.application.QuoteSending.Receipt> send(@PathVariable UUID orderId, @PathVariable UUID quoteId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @Valid @RequestBody SendRequest request) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(sending.send(orderId, quoteId, request.workOrderVersion(), key));
    }
    @PostMapping("/{quoteId}/revoke-link")
    public ResponseEntity<br.com.fixflow.modules.quotes.application.QuoteLinks.Receipt> revoke(@PathVariable UUID orderId, @PathVariable UUID quoteId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @Valid @RequestBody SendRequest request) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(links.revoke(orderId, quoteId, request.workOrderVersion(), key));
    }
    @PostMapping("/{quoteId}/resend-link")
    public ResponseEntity<br.com.fixflow.modules.quotes.application.QuoteLinks.Receipt> resend(@PathVariable UUID orderId, @PathVariable UUID quoteId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @Valid @RequestBody SendRequest request) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(links.resend(orderId, quoteId, request.workOrderVersion(), key));
    }
}
