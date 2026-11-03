package br.com.fixflow.modules.identity;

import br.com.fixflow.modules.identity.application.port.RefreshSecrets;
import br.com.fixflow.support.MembershipFixtures;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "fixflow.auth.basic-enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
class AuthenticationT9IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired Environment environment;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;
    @Autowired JwtDecoder decoder;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired RefreshSecrets secrets;
    @Autowired JdbcTemplate runtime;

    @Test
    void t9RejectsMissingExpiredTamperedAndMalformedTokens() throws Exception {
        try (var fixture = new MembershipFixtures(environment, encoder)) {

            String path = selection(fixture.tenantA);

            mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"));

            String basic = java.util.Base64.getEncoder().encodeToString((fixture.email + ":" + fixture.password).getBytes(java.nio.charset.StandardCharsets.UTF_8));

            mvc.perform(get(path).header("Authorization", "Basic " + basic)).andExpect(status().isUnauthorized());
            var login = login(fixture, fixture.password);
            assertEquals(200, login.getResponse().getStatus());
            String token = access(login);
            var jwt = decoder.decode(token);
            assertEquals(900, java.time.Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).toSeconds());
            assertEquals("RS256", jwt.getHeaders().get("alg"));
            assertNotNull(jwt.getHeaders().get("kid"));
            assertEquals(fixture.userId.toString(), jwt.getSubject());
            mvc.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
            String[] pieces = token.split("\\.");
            pieces[2] = (pieces[2].startsWith("A") ? "B" : "A") + pieces[2].substring(1);
            mvc.perform(get(path).header("Authorization", "Bearer " + String.join(".", pieces))).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
            for (String invalid : List.of(mint(fixture, "fixflow", "fixflow-api", Instant.now().minusSeconds(1), true),
                    mint(fixture, "other", "fixflow-api", Instant.now().plusSeconds(900), true),
                    mint(fixture, "fixflow", "other", Instant.now().plusSeconds(900), true),
                    mint(fixture, "fixflow", "fixflow-api", Instant.now().plusSeconds(900), false))) {
                mvc.perform(get(path).header("Authorization", "Bearer " + invalid)).andExpect(status().isUnauthorized());
            }
        }
    }

    @Test
    void rotatesStoresOnlyHashAndReuseRevokesEntireFamily() throws Exception {
        try (var fixture = new MembershipFixtures(environment, encoder)) {
            var first = login(fixture, fixture.password);
            var original = refreshCookie(first);
            assertTrue(original.isHttpOnly());
            assertTrue(original.getSecure());
            assertEquals("Strict", original.getAttribute("SameSite"));
            assertEquals("/api/v1/auth", original.getPath());
            assertFalse(first.getResponse().getContentAsString().contains(original.getValue()));
            assertEquals("no-store", first.getResponse().getHeader("Cache-Control"));
            assertEquals(secrets.hash(original.getValue()), owner().queryForObject("SELECT token_hash FROM refresh_token WHERE token_hash = ?", String.class, secrets.hash(original.getValue())));
            var second = refresh(original);
            assertEquals(200, second.getResponse().getStatus());
            var latest = refreshCookie(second);
            assertNotEquals(original.getValue(), latest.getValue());
            var independent = refreshCookie(login(fixture, fixture.password));
            assertEquals(401, refresh(original).getResponse().getStatus());
            assertEquals(401, refresh(latest).getResponse().getStatus());
            assertEquals(200, refresh(independent).getResponse().getStatus());
            assertEquals(1, owner().queryForObject("SELECT count(*) FROM auth_session WHERE user_id = ? AND revoked_at IS NOT NULL", Integer.class, fixture.userId));
            assertThrows(org.springframework.dao.DataAccessException.class, () -> runtime.queryForList("SELECT * FROM refresh_token"));
        }
    }

    @Test
    void concurrentRefreshCannotProduceTwoValidDescendants() throws Exception {
        try (var fixture = new MembershipFixtures(environment, encoder);
             var executor = Executors.newFixedThreadPool(2)) {
            Cookie original = refreshCookie(login(fixture, fixture.password));
            var start = new CountDownLatch(1);
            java.util.concurrent.Callable<MvcResult> task = () -> { start.await(); return refresh(original); };
            var first = executor.submit(task);
            var second = executor.submit(task);
            start.countDown();
            var a = first.get(10, TimeUnit.SECONDS);
            var b = second.get(10, TimeUnit.SECONDS);
            assertEquals(List.of(200, 401), java.util.stream.Stream.of(a, b).map(result -> result.getResponse().getStatus()).sorted().toList());
            var success = a.getResponse().getStatus() == 200 ? a : b;
            assertEquals(401, refresh(refreshCookie(success)).getResponse().getStatus());
        }
    }

    @Test
    void fifthFailureBlocksCorrectPasswordAndLockStateCommitsAcrossRequests() throws Exception {
        try (var fixture = new MembershipFixtures(environment, encoder)) {
            for (int i = 0; i < 5; i++) assertEquals(401, login(fixture, "wrong-password").getResponse().getStatus());
            assertEquals(5, owner().queryForObject("SELECT failed_attempts FROM users WHERE id = ?", Integer.class, fixture.userId));
            assertTrue(owner().queryForObject("SELECT locked_until > clock_timestamp() FROM users WHERE id = ?", Boolean.class, fixture.userId));
            assertEquals(401, login(fixture, fixture.password).getResponse().getStatus());
            assertEquals(0, owner().queryForObject("SELECT count(*) FROM auth_session WHERE user_id = ?", Integer.class, fixture.userId));
            owner().update("UPDATE users SET locked_until = clock_timestamp() - interval '1 second' WHERE id = ?", fixture.userId);
            assertEquals(200, login(fixture, fixture.password).getResponse().getStatus());
            assertEquals(0, owner().queryForObject("SELECT failed_attempts FROM users WHERE id = ?", Integer.class, fixture.userId));
        }
    }

    @Test
    void concurrentFailuresAreCountedAndLaterLocksAreProgressive() throws Exception {
        try (var fixture = new MembershipFixtures(environment, encoder);
             var executor = Executors.newFixedThreadPool(5)) {
            var start = new CountDownLatch(1);
            var results = new java.util.ArrayList<java.util.concurrent.Future<MvcResult>>();
            for (int i = 0; i < 5; i++) results.add(executor.submit(() -> { start.await(); return login(fixture, "wrong-password"); }));
            start.countDown();
            for (var result : results) assertEquals(401, result.get(15, TimeUnit.SECONDS).getResponse().getStatus());
            assertEquals(5, owner().queryForObject("SELECT failed_attempts FROM users WHERE id = ?", Integer.class, fixture.userId));
            owner().update("UPDATE users SET locked_until = clock_timestamp() - interval '1 second' WHERE id = ?", fixture.userId);
            for (int i = 0; i < 5; i++) assertEquals(401, login(fixture, "wrong-password").getResponse().getStatus());
            assertEquals(2, owner().queryForObject("SELECT lock_level FROM users WHERE id = ?", Integer.class, fixture.userId));
            assertTrue(owner().queryForObject("SELECT locked_until > clock_timestamp() + interval '4 minutes' FROM users WHERE id = ?", Boolean.class, fixture.userId));
        }
    }

    @Test
    void refreshRespectsTokenAndAbsoluteFamilyExpiry() throws Exception {
        try (var fixture = new MembershipFixtures(environment, encoder)) {
            var cookie = refreshCookie(login(fixture, fixture.password));
            owner().update("UPDATE refresh_token SET expires_at = clock_timestamp() - interval '1 second' WHERE token_hash = ?", secrets.hash(cookie.getValue()));
            assertEquals(401, refresh(cookie).getResponse().getStatus());
            cookie = refreshCookie(login(fixture, fixture.password));
            owner().update("UPDATE auth_session SET absolute_expires_at = clock_timestamp() + interval '1 day' WHERE id = (SELECT family_id FROM refresh_token WHERE token_hash = ?)", secrets.hash(cookie.getValue()));
            var rotated = refresh(cookie);
            assertEquals(200, rotated.getResponse().getStatus());
            assertTrue(refreshCookie(rotated).getMaxAge() <= 86400);
            owner().update("UPDATE auth_session SET absolute_expires_at = clock_timestamp() - interval '1 second' WHERE user_id = ?", fixture.userId);
            assertEquals(401, refresh(refreshCookie(rotated)).getResponse().getStatus());
        }
    }

    @Test
    void csrfGuardRejectsMissingHeaderAndUntrustedOrigin() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/refresh").header("X-Auth-Request", "true").header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/refresh").header("X-Auth-Request", "true")).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenIsBoundToTenantAndSuspensionsTakeEffectImmediately() throws Exception {
        try (var fixture = new MembershipFixtures(environment, encoder)) {
            fixture.activateSecondMembership();
            var session = login(fixture, fixture.password);
            String token = access(session);
            mvc.perform(get(selection(fixture.tenantB)).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
            fixture.suspendUser();
            mvc.perform(get(selection(fixture.tenantA)).header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
            assertEquals(401, refresh(refreshCookie(session)).getResponse().getStatus());
        }
    }

    private MvcResult login(MembershipFixtures fixture, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").header("X-Auth-Request", "true")
                .header("Origin", "http://localhost:4200").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("email", fixture.email.toUpperCase(java.util.Locale.ROOT), "password", password, "tenantId", fixture.tenantA))))
                .andReturn();
    }
    private MvcResult refresh(Cookie cookie) throws Exception {
        return mvc.perform(post("/api/v1/auth/refresh").header("X-Auth-Request", "true").cookie(cookie)).andReturn();
    }
    private Cookie refreshCookie(MvcResult result) { return result.getResponse().getCookie("fixflow_refresh"); }
    private String access(MvcResult result) throws Exception { return mapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText(); }
    private String selection(UUID tenant) { return "/api/oficinas/" + tenant + "/selecao"; }
    private JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(environment.getRequiredProperty("spring.flyway.url"),
                environment.getRequiredProperty("spring.flyway.user"), environment.getRequiredProperty("spring.flyway.password")));
    }
    private String mint(MembershipFixtures fixture, String issuer, String audience, Instant expiry, boolean tenant) {
        var claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience)).subject(fixture.userId.toString())
                .issuedAt(Instant.now().minusSeconds(1800)).expiresAt(expiry).id(UUID.randomUUID().toString()).claim("role", "OWNER");
        if (tenant) claims.claim("tid", fixture.tenantA.toString());
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims.build())).getTokenValue();
    }
}
