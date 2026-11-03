package br.com.fixflow.shared.infrastructure.registration.usecase;

import br.com.fixflow.shared.application.registration.usecase.AccountRegistration;
import br.com.fixflow.shared.application.registration.dto.RegisteredAccount;
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
    public RegisteredAccount register(String companyName, String fullName, String email, String password) {
        if (!validLabel(companyName, 150) || !validLabel(fullName, 150) || !validLabel(email, 254)
                || password == null || password.isBlank() || password.length() < 10
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new InvalidRegistrationException();
        }
        String hash = encoder.encode(password);
        try {
            return jdbc.queryForObject("SELECT * FROM public.register_owner(?, ?, ?, ?)",
                    (rs, row) -> new RegisteredAccount(rs.getObject("user_id", UUID.class),
                            rs.getObject("tenant_id", UUID.class), "OWNER"),
                    companyName.strip(), fullName.strip(), email.strip().toLowerCase(Locale.ROOT), hash);
        } catch (DuplicateKeyException exception) {
            throw new EmailAlreadyRegisteredException();
        }
    }

    private static boolean validLabel(String value, int max) {
        return value != null && !value.isBlank() && value.strip().length() <= max;
    }
}
