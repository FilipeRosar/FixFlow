package br.com.fixflow.modules.quotes.application;

import br.com.fixflow.modules.quotes.domain.Quote;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface Quotes {
    record History(List<Quote> quotes, int workOrderVersion) { }
    record Draft(int workOrderVersion, List<Quote.Item> items, String discountType, BigDecimal discountValue) { }
    History list(UUID order);
    History create(UUID order, Draft draft);
    History update(UUID order, UUID quote, Draft draft);
}
