package br.com.fixflow.shared.application.registration.dto;

public record RegistrationAddress(String street, String number, String complement, String neighborhood,
                                  String city, String state, String postalCode) {
    @Override
    public String toString() { return "RegistrationAddress[redacted]"; }
}
