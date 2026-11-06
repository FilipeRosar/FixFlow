package br.com.fixflow.modules.quotes.infrastructure;

import br.com.fixflow.modules.quotes.application.PublicQuoteDecisions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class JdbcPublicQuoteDecisions implements PublicQuoteDecisions {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public JdbcPublicQuoteDecisions(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome decide(String hash, String decision, String contentHash, String reason, String keyHash, String ip, String userAgent) {
        String json = jdbc.queryForObject("SELECT public.decide_public_quote(?, ?, ?, ?, ?, ?, ?)::text", String.class,
                hash, decision, contentHash, reason, keyHash, ip, userAgent);
        return mapper.readValue(json, Outcome.class);
    }
}
