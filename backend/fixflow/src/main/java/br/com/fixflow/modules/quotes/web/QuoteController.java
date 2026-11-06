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
    public QuoteController(Quotes quotes) { this.quotes = quotes; }
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
}
