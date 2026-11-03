package br.com.fixflow.shared.application.registration.usecase;

import java.util.Optional;

public interface PostalCodeLookup {
    Optional<Address> lookup(String postalCode);
    record Address(String street, String neighborhood, String city, String state) {}
}
