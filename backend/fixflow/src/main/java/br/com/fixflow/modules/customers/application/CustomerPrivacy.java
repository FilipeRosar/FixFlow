package br.com.fixflow.modules.customers.application;

import br.com.fixflow.modules.customers.domain.CustomerExport;
import java.util.UUID;

public interface CustomerPrivacy {
    CustomerExport export(UUID customerId);
    void anonymize(UUID customerId);
}
