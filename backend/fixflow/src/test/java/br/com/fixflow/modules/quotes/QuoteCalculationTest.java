package br.com.fixflow.modules.quotes;

import br.com.fixflow.modules.quotes.domain.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class QuoteCalculationTest {
    Quote.Item item(String type, String quantity, String price) {
        return new Quote.Item(type, type.equals("PART") ? UUID.randomUUID() : null, "Item", new BigDecimal(quantity), new BigDecimal(price));
    }
    @Test void sumsProductsByCategoryThenRoundsHalfUpAndAppliesFixedDiscount() {
        var totals = QuoteCalculation.calculate(List.of(item("SERVICE", "0.333", "10.00"), item("SERVICE", "0.333", "10.00"), item("PART", "1.001", "5.00")), "FIXED", new BigDecimal("1.00"));
        assertEquals(new BigDecimal("6.66"), totals.laborSubtotal());
        assertEquals(new BigDecimal("5.01"), totals.partsSubtotal());
        assertEquals(new BigDecimal("10.67"), totals.total());
    }
    @Test void percentageIsRoundedHalfUpAndFullDiscountAllowsZeroTotal() {
        var items = List.of(item("SERVICE", "1", "10.05"));
        assertEquals(new BigDecimal("1.01"), QuoteCalculation.calculate(items, "PERCENT", new BigDecimal("10")).discountAmount());
        assertEquals(new BigDecimal("9.04"), QuoteCalculation.calculate(items, "PERCENT", new BigDecimal("10")).total());
        assertEquals(new BigDecimal("0.00"), QuoteCalculation.calculate(items, "PERCENT", new BigDecimal("100")).total());
        assertEquals(new BigDecimal("0.00"), QuoteCalculation.calculate(items, "FIXED", new BigDecimal("10.05")).total());
    }
    @Test void rejectsInvalidDiscountsQuantitiesPrecisionTypesAndOverflow() {
        var items = List.of(item("SERVICE", "1", "10"));
        for (var discount : List.of("-1", "10.01", "0.001")) assertThrows(IllegalArgumentException.class, () -> QuoteCalculation.calculate(items, "FIXED", new BigDecimal(discount)));
        assertThrows(IllegalArgumentException.class, () -> QuoteCalculation.calculate(items, "PERCENT", new BigDecimal("100.01")));
        assertThrows(IllegalArgumentException.class, () -> QuoteCalculation.calculate(List.of(), "FIXED", BigDecimal.ZERO));
        for (var bad : List.of(item("SERVICE", "0", "10"), item("SERVICE", "-1", "10"), item("PART", "1", "-1"), item("SERVICE", "1.0001", "10"), item("SERVICE", "1", "1.001"), item("SERVICE", "2", "999999999999.99"), item("INVALID", "1", "10")))
            assertThrows(IllegalArgumentException.class, () -> QuoteCalculation.calculate(List.of(bad), "FIXED", BigDecimal.ZERO));
    }
}
