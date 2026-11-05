package br.com.fixflow.modules.customers.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CustomerExport(int schemaVersion, Instant exportedAt, Profile customer,
        List<ExportEquipment> equipment, List<ExportOrder> workOrders) {
    public record Profile(UUID id, String name, String document, Instant createdAt, Instant updatedAt,
            Instant deletedAt, Instant anonymizedAt, List<Customer.Contact> contacts) { }
    public record ExportEquipment(UUID id, String category, String brand, String model, String serialNumber,
            String description, Instant createdAt, Instant updatedAt, Instant deletedAt) { }
    public record ExportOrder(UUID id, String status, Instant createdAt) { }
}
