package br.com.fixflow.modules.workorders.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record WorkOrder(UUID id, long number, UUID customerId, UUID equipmentId, String status,
                        String reportedIssue, List<String> receivedAccessories, String physicalCondition,
                        Instant openedAt, int version, String customerName, String equipmentLabel, String serialNumber, UUID technicianId, String technicianName, Diagnosis diagnosis,
                        Instant readyAt, Instant deliveredAt, Integer warrantyDays, LocalDate warrantyUntil,
                        boolean paymentSettled, boolean deliveryPaymentOverride, String deliveryReason,
                        String deliveredByName, String warrantyTerms) { }
