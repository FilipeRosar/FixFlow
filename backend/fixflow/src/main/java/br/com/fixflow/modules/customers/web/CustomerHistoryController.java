package br.com.fixflow.modules.customers.web;

import br.com.fixflow.modules.customers.application.CustomerHistory;
import jakarta.validation.constraints.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@Validated
@RequestMapping("/api/v1/customers/{customerId}/history")
public class CustomerHistoryController {
    private final CustomerHistory history;
    public CustomerHistoryController(CustomerHistory history) { this.history = history; }
    @GetMapping public CustomerHistory.Page load(@PathVariable UUID customerId,
            @RequestParam(defaultValue = "ALL") @Pattern(regexp = "ALL|WORK_ORDER|EQUIPMENT") String type,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "desc") @Pattern(regexp = "asc|desc") String sort) {
        return history.load(customerId, type, page, size, sort);
    }
}
