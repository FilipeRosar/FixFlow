package br.com.fixflow.modules.customers.web;

import br.com.fixflow.modules.customers.application.Customers;
import br.com.fixflow.modules.customers.domain.Customer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import java.net.URI;
import java.util.UUID;

@RestController
@Validated
@RequestMapping("/api/v1/customers")
public class CustomerController {
    private final Customers customers;
    public CustomerController(Customers customers) { this.customers = customers; }
    @GetMapping public Customers.Page search(@RequestParam(defaultValue = "") @Size(max = 150) String q,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return customers.search(q, page, size);
    }
    @GetMapping("/{id}") public Customer get(@PathVariable UUID id) { return customers.get(id); }
    @PostMapping public ResponseEntity<Customer> create(@Valid @RequestBody CustomerRequest request) {
        Customer customer = customers.create(request.customer());
        return ResponseEntity.created(URI.create("/api/v1/customers/" + customer.id())).body(customer);
    }
    @PutMapping("/{id}") public Customer update(@PathVariable UUID id, @Valid @RequestBody CustomerRequest request) {
        return customers.update(id, request.customer());
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) { customers.delete(id); }
}
