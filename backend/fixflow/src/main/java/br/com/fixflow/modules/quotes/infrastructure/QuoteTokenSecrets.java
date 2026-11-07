package br.com.fixflow.modules.quotes.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

final class QuoteTokenSecrets {
    private QuoteTokenSecrets() { }
    record Secret(String token, String hash) { }
    static Secret generate() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new Secret(token, hash(token));
    }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
