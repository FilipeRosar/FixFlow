package br.com.fixflow.modules.workorders.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

public final class DeliveryWarranty {
    private DeliveryWarranty() { }
    public static LocalDate until(Instant deliveredAt, int days) {
        Objects.requireNonNull(deliveredAt);
        if (days < 0 || days > 3650) throw new IllegalArgumentException("Prazo adicional deve estar entre 0 e 3650 dias.");
        return days == 0 ? null : deliveredAt.atZone(ZoneId.of("America/Sao_Paulo")).toLocalDate().plusDays(days);
    }
}
