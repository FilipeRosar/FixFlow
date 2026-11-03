package br.com.fixflow.shared.web.registration.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;

public record RegistrationRequest(
        @NotBlank
        @Size(max = 150)
        String companyName,
        @NotBlank
        @Size(max = 150)
        String firstName,
        @NotBlank @Size(max = 150) String lastName,
        @NotBlank @Pattern(regexp = "(?:[0-9]{11}|[0-9]{14})") String document,
        @NotNull @Valid Address address,
        @NotBlank @Pattern(regexp = "[0-9]{10,11}") String phone,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 8, max = 72) @Pattern(regexp = "^(?=.*[A-Z])(?=.*[a-z])(?=.*[0-9])[\\s\\S]*$") String password) {
    public RegistrationRequest {
        companyName = companyName == null ? null : companyName.strip();
        firstName = firstName == null ? null : firstName.strip();
        lastName = lastName == null ? null : lastName.strip();
        document = normalize(document);
        phone = normalize(phone);

        email = email == null ? null : email.strip();
    }

    public record Address(
            @NotBlank @Size(max = 150) String street,
            @NotBlank @Size(max = 20) String number,
            @Size(max = 100) String complement,
            @NotBlank @Size(max = 100) String neighborhood,
            @NotBlank @Size(max = 100) String city,
            @NotBlank @Pattern(regexp = "[A-Z]{2}") String state,
            @NotBlank @Pattern(regexp = "[0-9]{8}") String postalCode) {
        public Address {
            street = trim(street); number = trim(number); complement = trim(complement);
            neighborhood = trim(neighborhood); city = trim(city);
            state = state == null ? null : state.strip().toUpperCase(java.util.Locale.ROOT);
            postalCode = postalCode == null ? null : postalCode.replaceAll("[\\s-]", "");
        }
        private static String trim(String value) { return value == null ? null : value.strip(); }
        public br.com.fixflow.shared.application.registration.dto.RegistrationAddress toApplication() {
            return new br.com.fixflow.shared.application.registration.dto.RegistrationAddress(
                    street, number, complement, neighborhood, city, state, postalCode);
        }
        @Override
        public String toString() { return "Address[redacted]"; }
    }

    private static String normalize(String value) {
        return value == null ? null : value.replaceAll("[./()\\s-]", "");
    }

    @Override
    public String toString() {
        return "RegistrationRequest[redacted]";
    }
}
