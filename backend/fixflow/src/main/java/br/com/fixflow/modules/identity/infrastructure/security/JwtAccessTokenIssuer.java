package br.com.fixflow.modules.identity.infrastructure.security;

import br.com.fixflow.modules.identity.application.port.AccessTokenIssuer;
import br.com.fixflow.modules.identity.domain.SessionIdentity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class JwtAccessTokenIssuer implements AccessTokenIssuer {
    private final JwtEncoder encoder;
    private final String issuer;
    private final String keyId;
    public JwtAccessTokenIssuer(JwtEncoder encoder, @Value("${fixflow.auth.issuer:fixflow}") String issuer,
                                 @Value("${fixflow.auth.key-id:fixflow-rsa-1}") String keyId) {
        this.encoder = encoder;
        this.issuer = issuer;
        this.keyId = keyId;
    }
    @Override
    public String issue(SessionIdentity identity, Instant issuedAt) {
        var claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of("fixflow-api"))
                .subject(identity.userId().toString()).issuedAt(issuedAt).expiresAt(issuedAt.plusSeconds(900))
                .id(UUID.randomUUID().toString()).claim("tid", identity.tenantId().toString())
                .claim("role", identity.role()).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build(), claims)).getTokenValue();
    }
}
