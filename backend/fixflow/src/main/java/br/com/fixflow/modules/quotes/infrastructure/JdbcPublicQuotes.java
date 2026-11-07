package br.com.fixflow.modules.quotes.infrastructure;

import br.com.fixflow.modules.quotes.application.PublicQuotes;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.Optional;

@Service
public class JdbcPublicQuotes implements PublicQuotes {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public JdbcPublicQuotes(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<View> findByHash(String hash) {
        // The database derives and restores its own tenant context. Never accept a tenant or OS ID here.
        String json = jdbc.queryForObject("SELECT public.read_public_quote(?)::text", String.class, hash);
        return json == null ? Optional.empty() : Optional.of(mapper.readValue(json, View.class));
    }
}
