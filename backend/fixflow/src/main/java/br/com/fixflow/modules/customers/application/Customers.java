package br.com.fixflow.modules.customers.application;

import br.com.fixflow.modules.customers.domain.Customer;
import java.util.List;
import java.util.UUID;

public interface Customers {
    record Page(List<Customer> content, long totalElements, int page, int size) { }
    Page search(String query, int page, int size);
    Customer get(UUID id);
    Customer create(Customer customer);
    Customer update(UUID id, Customer customer);
    void delete(UUID id);
}
