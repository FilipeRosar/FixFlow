package br.com.fixflow.modules.equipment.application;

import br.com.fixflow.modules.equipment.domain.Equipment;
import java.util.List;
import java.util.UUID;

public interface EquipmentManagement {
    record Page(List<Equipment> content, long totalElements, int page, int size) { }
    Page search(String serial, UUID customerId, int page, int size);
    Equipment get(UUID id);
    Equipment create(Equipment equipment);
    Equipment update(UUID id, Equipment equipment);
    Equipment describe(UUID id, String description);
    void delete(UUID id);
}
