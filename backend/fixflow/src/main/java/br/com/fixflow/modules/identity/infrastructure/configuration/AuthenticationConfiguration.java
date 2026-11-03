package br.com.fixflow.modules.identity.infrastructure.configuration;

import br.com.fixflow.modules.identity.application.port.*;
import br.com.fixflow.modules.identity.application.usecase.AuthenticateSession;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;

import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

@Configuration(proxyBeanMethods = false)
public class AuthenticationConfiguration {
    @Bean
    Clock authenticationClock() { return Clock.systemUTC(); }

    @Bean
    AuthenticateSession authenticateSession(SessionStore store, PasswordVerifier passwords, AccessTokenIssuer tokens,
                                             RefreshSecrets secrets, Clock clock) {
        return new AuthenticateSession(store, passwords, tokens, secrets, clock);
    }

    @Bean
    KeyPair authenticationKeyPair(ResourceLoader resources,
            @Value("${fixflow.auth.private-key:}") String privateLocation,
            @Value("${fixflow.auth.public-key:}") String publicLocation,
            @Value("${fixflow.auth.ephemeral-keys:false}") boolean ephemeral) throws Exception {
        if (privateLocation.isBlank() && publicLocation.isBlank() && ephemeral) {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        }
        if (privateLocation.isBlank() || publicLocation.isBlank()) {
            throw new IllegalStateException("Configure fixflow.auth.private-key e fixflow.auth.public-key (PEM RSA).");
        }
        var factory = KeyFactory.getInstance("RSA");
        var privateKey = (RSAPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(readPem(resources, privateLocation)));
        var publicKey = (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(readPem(resources, publicLocation)));
        if (publicKey.getModulus().bitLength() < 2048 || !privateKey.getModulus().equals(publicKey.getModulus())) {
            throw new IllegalStateException("Par RSA inválido: use chaves correspondentes de pelo menos 2048 bits.");
        }
        return new KeyPair(publicKey, privateKey);
    }

    private static byte[] readPem(ResourceLoader resources, String location) throws Exception {
        try (var input = resources.getResource(location).getInputStream()) {
            String pem = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII)
                    .replaceAll("-----[^-]+-----", "").replaceAll("\\s", "");
            return Base64.getDecoder().decode(pem);
        }
    }

    @Bean
    JwtEncoder accessTokenEncoder(KeyPair authenticationKeyPair, @Value("${fixflow.auth.key-id:fixflow-rsa-1}") String keyId) {
        var key = new RSAKey.Builder((RSAPublicKey) authenticationKeyPair.getPublic())
                .privateKey(authenticationKeyPair.getPrivate()).keyID(keyId).build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
    }

    @Bean
    JwtDecoder accessTokenDecoder(KeyPair authenticationKeyPair, Clock clock,
            @Value("${fixflow.auth.issuer:fixflow}") String issuer) {
        var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) authenticationKeyPair.getPublic()).build();
        var time = new JwtTimestampValidator(Duration.ZERO);
        time.setClock(clock);
        OAuth2TokenValidator<Jwt> required = jwt -> jwt.getExpiresAt() != null && jwt.getIssuedAt() != null
                && jwt.getId() != null && jwt.getAudience().contains("fixflow-api")
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(time, new JwtIssuerValidator(issuer), required));
        return decoder;
    }
}
