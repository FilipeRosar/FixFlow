package br.com.fixflow.modules.customers.domain;

import java.util.List;
import java.util.UUID;

public record Customer(UUID id, String name, String document, List<Contact> contacts) {
    public record Contact(String type, String value, String label) { }
}
