package br.com.fixflow.modules.billing.web;

import br.com.fixflow.modules.billing.application.Finance;
import java.time.Instant;
import java.time.Duration;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/finance")
public class FinanceController {
    private final Finance finance;
    public FinanceController(Finance finance) { this.finance = finance; }

    @GetMapping
    public Finance.Report report(@RequestParam Instant from, @RequestParam Instant to,
            @RequestParam(defaultValue = "") String status, @RequestParam(defaultValue = "") String method,
            @RequestParam(defaultValue = "0") int page) {
        if (!from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(367)) > 0 || page < 0
                || !Set.of("", "CONFIRMED", "VOIDED").contains(status)
                || !Set.of("", "PIX", "CASH", "CREDIT_CARD", "DEBIT_CARD", "BANK_TRANSFER", "OTHER").contains(method))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Confira o período (máximo de um ano) e os filtros.");
        return finance.report(from, to, status, method, page);
    }
}
