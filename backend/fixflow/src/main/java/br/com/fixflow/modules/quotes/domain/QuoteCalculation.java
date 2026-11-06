package br.com.fixflow.modules.quotes.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

public record QuoteCalculation(BigDecimal laborSubtotal, BigDecimal partsSubtotal, BigDecimal discountAmount, BigDecimal total) {
    private static final BigDecimal LIMIT = new BigDecimal("999999999999.99");
    private static BigDecimal money(BigDecimal amount) { return amount.setScale(2, RoundingMode.HALF_UP); }
    public static QuoteCalculation calculate(List<Quote.Item> items, String discountType, BigDecimal discountValue) {
        if (items == null || items.isEmpty() || items.size() > 100) throw new IllegalArgumentException("Informe entre 1 e 100 itens.");
        if (discountType == null || !Set.of("FIXED", "PERCENT").contains(discountType) || discountValue == null ||
                discountValue.signum() < 0 || discountValue.scale() > 2 || discountValue.compareTo(LIMIT) > 0)
            throw new IllegalArgumentException("Desconto inválido.");
        BigDecimal labor = BigDecimal.ZERO, parts = BigDecimal.ZERO;
        for (var item : items) {
            if (item == null || item.type() == null || !Set.of("SERVICE", "PART").contains(item.type()) ||
                    item.type().equals("PART") != (item.partId() != null) || item.description() == null ||
                    item.description().isBlank() || item.description().length() > 500 || item.quantity() == null ||
                    item.quantity().signum() <= 0 || item.quantity().scale() > 3 || item.quantity().compareTo(new BigDecimal("99999999999.999")) > 0 ||
                    item.unitPrice() == null || item.unitPrice().signum() < 0 || item.unitPrice().scale() > 2 || item.unitPrice().compareTo(LIMIT) > 0)
                throw new IllegalArgumentException("Item inválido: informe tipo, descrição, quantidade positiva e preço não negativo.");
            BigDecimal amount = item.quantity().multiply(item.unitPrice());
            if (item.type().equals("SERVICE")) labor = labor.add(amount); else parts = parts.add(amount);
        }
        labor = money(labor); parts = money(parts);
        BigDecimal subtotal = labor.add(parts);
        BigDecimal discount;
        if (discountType.equals("PERCENT")) {
            if (discountValue.compareTo(new BigDecimal("100")) > 0) throw new IllegalArgumentException("Desconto percentual deve estar entre 0 e 100.");
            discount = money(subtotal.multiply(discountValue).movePointLeft(2));
        } else discount = money(discountValue);
        if (discount.compareTo(subtotal) > 0) throw new IllegalArgumentException("Desconto não pode superar o subtotal.");
        if (subtotal.compareTo(LIMIT) > 0) throw new IllegalArgumentException("Subtotal excede o limite monetário.");
        return new QuoteCalculation(labor, parts, discount, subtotal.subtract(discount));
    }
}
