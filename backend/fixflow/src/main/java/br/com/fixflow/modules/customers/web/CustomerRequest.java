package br.com.fixflow.modules.customers.web;

import br.com.fixflow.modules.customers.domain.Customer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record CustomerRequest(@NotBlank @Size(max = 150) String name,
        @Size(max = 18) @Pattern(regexp = "[0-9./ -]*", message = "Documento inválido") String document,
        @NotNull @Size(max = 20) List<@NotNull @Valid ContactRequest> contacts) {
    public record ContactRequest(@NotNull @Pattern(regexp = "PHONE|WHATSAPP|EMAIL") String type,
            @NotBlank @Size(max = 254) String value, @Size(max = 80) String label) { }
    public Customer customer() {
        return new Customer(null, name.strip(), document == null ? null : document.replaceAll("[^0-9]", ""),
                contacts.stream().map(c -> new Customer.Contact(c.type(), c.value().strip(), c.label() == null ? "" : c.label().strip())).toList());
    }
}
