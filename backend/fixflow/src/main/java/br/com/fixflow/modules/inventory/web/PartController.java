package br.com.fixflow.modules.inventory.web;

import br.com.fixflow.modules.inventory.application.Parts;
import br.com.fixflow.modules.inventory.domain.Part;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;

@RestController
@Validated
@RequestMapping("/api/v1/parts")
public class PartController {
    private final Parts parts;
    public PartController(Parts parts) { this.parts = parts; }
    public record Request(@NotBlank @Size(max = 80) String sku, @NotBlank @Size(max = 200) String name,
                          @NotBlank @Size(max = 20) String unit,
                          @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal cost,
                          @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal salePrice,
                          @Null BigDecimal quantity, @Null BigDecimal quantityOnHand,
                          @Min(0) Integer version,
                          @NotNull @DecimalMin("0") @Digits(integer = 11, fraction = 3) BigDecimal minQuantity) { }
    @GetMapping public List<Part> list() { return parts.list(); }
    @GetMapping("/{id}") public Part get(@PathVariable UUID id) { return parts.get(id); }
    @PutMapping("/{id}") public Part update(@PathVariable UUID id, @Valid @RequestBody Request request) {
        if (request.version() == null) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Versão obrigatória.");
        return parts.update(id, request.sku().strip().toUpperCase(Locale.ROOT), request.name().strip(), request.unit().strip(),
                request.cost(), request.salePrice(), request.minQuantity(), request.version());
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @RequestParam @Min(0) int version) { parts.delete(id, version); }
    @PostMapping public ResponseEntity<Part> create(@Valid @RequestBody Request request) {
        Part part = parts.create(request.sku().strip().toUpperCase(Locale.ROOT), request.name().strip(), request.unit().strip(),
                request.cost(), request.salePrice(), request.minQuantity());
        return ResponseEntity.created(URI.create("/api/v1/parts/" + part.id())).body(part);
    }
}
