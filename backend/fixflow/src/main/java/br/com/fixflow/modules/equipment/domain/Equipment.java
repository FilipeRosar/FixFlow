package br.com.fixflow.modules.equipment.domain;

import java.util.UUID;

public record Equipment(UUID id, UUID customerId, String customerName, boolean customerDeleted,
        String category, String brand, String model, String serialNumber, String description) { }
