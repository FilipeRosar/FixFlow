package br.com.fixflow.modules.customers.application;

import br.com.fixflow.modules.customers.domain.CustomerHistoryEntry;
import java.util.List;
import java.util.UUID;

public interface CustomerHistory {
    record Page(UUID customerId, String customerName, List<CustomerHistoryEntry> content,
            long totalElements, int page, int size, String sort, boolean workOrdersVisible) { }
    Page load(UUID customerId, String type, int page, int size, String sort);
}
