package br.com.fixflow.modules.tenants.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

public record CompanySettings(String logoDataUrl, String warrantyTerms, int quoteValidityDays, boolean allowNegativeStock) {
    public CompanySettings {
        if (warrantyTerms == null || warrantyTerms.length() > 10000 || quoteValidityDays < 1 || quoteValidityDays > 365)
            throw new IllegalArgumentException("Configurações inválidas.");
    }
    public QuoteDefaults quoteDefaults(Instant issuedAt) {
        return new QuoteDefaults(issuedAt.plus(quoteValidityDays, ChronoUnit.DAYS), warrantyTerms, logoDataUrl);
    }
    public BigDecimal remainingStock(BigDecimal balance, BigDecimal quantity) {
        if (balance == null || quantity == null || quantity.signum() <= 0)
            throw new IllegalArgumentException("Quantidade deve ser positiva.");
        BigDecimal remaining = balance.subtract(quantity);
        if (!allowNegativeStock && remaining.signum() < 0) throw new InsufficientStockException();
        return remaining;
    }
}
