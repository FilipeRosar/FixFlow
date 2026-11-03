package br.com.fixflow.shared.infrastructure.registration.usecase;

import br.com.fixflow.shared.application.registration.usecase.AccountRegistration;
import br.com.fixflow.shared.application.registration.dto.RegisteredAccount;
import br.com.fixflow.shared.application.registration.dto.RegistrationAddress;
import br.com.fixflow.shared.application.registration.EmailAlreadyRegisteredException;
import br.com.fixflow.shared.application.registration.InvalidRegistrationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

@Service
public class OwnerAccountRegistration implements AccountRegistration {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    public OwnerAccountRegistration(JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    @Override
    @Transactional
    public RegisteredAccount register(String companyName, String firstName, String lastName, String email, String password, String document, RegistrationAddress address, String phone) {
        if (!validLabel(companyName, 150) || !validLabel(firstName, 150) || !validLabel(lastName, 150) || !validLabel(email, 254)
                || document == null || !document.matches("(?:[0-9]{11}|[0-9]{14})")
                || !validAddress(address) || phone == null || !phone.matches("[0-9]{10,11}")
                || password == null || password.isBlank() || password.length() < 8
                || !password.matches("^(?=.*[A-Z])(?=.*[a-z])(?=.*[0-9])[\\s\\S]*$")
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new InvalidRegistrationException();
        }
        String hash = encoder.encode(password);
        try {
            return jdbc.queryForObject("SELECT * FROM public.register_owner(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    (rs, row) -> new RegisteredAccount(rs.getObject("user_id", UUID.class),
                            rs.getObject("tenant_id", UUID.class), "OWNER"),
                    companyName.strip(), firstName.strip(), lastName.strip(), email.strip().toLowerCase(Locale.ROOT), hash,
                    document, address.street().strip(), address.number().strip(),
                    address.complement() == null ? null : address.complement().strip(), address.neighborhood().strip(),
                    address.city().strip(), address.state(), address.postalCode(), phone);
        } catch (DuplicateKeyException exception) {
            throw new EmailAlreadyRegisteredException();
        }
    }

    private static boolean validAddress(RegistrationAddress address) {
        return address != null && validLabel(address.street(), 150) && validLabel(address.number(), 20)
                && (address.complement() == null || address.complement().length() <= 100)
                && validLabel(address.neighborhood(), 100) && validLabel(address.city(), 100)
                && address.state() != null && address.state().matches("[A-Z]{2}")
                && address.postalCode() != null && address.postalCode().matches("[0-9]{8}");
    }

    private static boolean validLabel(String value, int max) {
        return value != null && !value.isBlank() && value.strip().length() <= max;
    }
}
