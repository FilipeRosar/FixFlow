package br.com.fixflow.modules.identity;

import br.com.fixflow.modules.identity.application.port.*;
import br.com.fixflow.modules.identity.application.usecase.AccountRecovery;
import br.com.fixflow.support.MembershipFixtures;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "fixflow.auth.basic-enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
class AccountRecoveryIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired Environment environment;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;
    @Autowired AccountRecovery recovery;
    @Autowired RefreshSecrets secrets;
    @MockitoBean ResetMail mail;
    @MockitoBean(name = "passwordRecoveryExecutor") ThreadPoolTaskExecutor executor;
    private static final String NEW_PASSWORD = "Newpass1";

    @BeforeEach
    void immediateQueue() {
        doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; }).when(executor).execute(any(Runnable.class));
    }

    @Test
    void responsesAreIdenticalForKnownUnknownAndSuspendedAccountsAndOnlyHashIsStored() throws Exception {
        try (var f = fixture()) {
            var known = request(f.email);
            var unknown = request(UUID.randomUUID() + "@example.invalid");
            f.suspendUser();
            var suspended = request(f.email);
            assertEquals(202, known.getResponse().getStatus());
            for (var response : new MvcResult[]{unknown, suspended}) {
                assertEquals(known.getResponse().getStatus(), response.getResponse().getStatus());
                assertEquals(known.getResponse().getContentAsString(), response.getResponse().getContentAsString());
                assertEquals(known.getResponse().getContentType(), response.getResponse().getContentType());
                assertEquals("no-store", response.getResponse().getHeader("Cache-Control"));
            }
            var token = ArgumentCaptor.forClass(String.class);
            verify(mail, times(1)).send(eq(f.email), token.capture());
            String hash = owner().queryForObject("SELECT token_hash FROM password_reset_token WHERE user_id = ?", String.class, f.userId);
            assertEquals(secrets.hash(token.getValue()), hash);
            assertNotEquals(token.getValue(), hash);
            double lifetime = owner().queryForObject("SELECT extract(epoch FROM expires_at - created_at) FROM password_reset_token WHERE user_id = ?", Double.class, f.userId);
            assertEquals(1800, lifetime, 1);
            assertFalse(known.getResponse().getContentAsString().contains(token.getValue()));
        }
    }

    @Test
    void resetChangesPasswordOnceAndRevokesAllRefreshSessions() throws Exception {
        try (var f = fixture()) {
            var firstSession = login(f.email, f.password);
            var secondSession = login(f.email, f.password);
            String token = issue(f.email);
            reset(token, NEW_PASSWORD).andExpect(status().isNoContent());
            reset(token, "Another1").andExpect(status().isBadRequest());
            assertTrue(encoder.matches(NEW_PASSWORD, owner().queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, f.userId)));
            assertEquals(401, login(f.email, f.password).getResponse().getStatus());
            assertEquals(200, login(f.email, NEW_PASSWORD).getResponse().getStatus());
            for (var session : new MvcResult[]{firstSession, secondSession}) {
                mvc.perform(post("/api/v1/auth/refresh").header("X-Auth-Request", "true")
                        .cookie(session.getResponse().getCookie("fixflow_refresh"))).andExpect(status().isUnauthorized());
            }
        }
    }

    @Test
    void expiredTokenCannotChangePassword() throws Exception {
        try (var f = fixture()) {
            String token = issue(f.email);
            owner().update("UPDATE password_reset_token SET expires_at = clock_timestamp() - interval '1 second' WHERE user_id = ?", f.userId);
            reset(token, NEW_PASSWORD).andExpect(status().isBadRequest());
            assertEquals(200, login(f.email, f.password).getResponse().getStatus());
        }
    }

    @Test
    void concurrentConsumptionSucceedsExactlyOnce() throws Exception {
        try (var f = fixture(); var threads = Executors.newFixedThreadPool(2)) {
            String token = issue(f.email);
            java.util.concurrent.Callable<Integer> work = () -> reset(token, NEW_PASSWORD).andReturn().getResponse().getStatus();
            var first = threads.submit(work); var second = threads.submit(work);
            var statuses = java.util.List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertTrue(statuses.contains(204)); assertTrue(statuses.contains(400));
        }
    }

    @Test
    void newRequestInvalidatesOldTokenAndCooldownAvoidsRepeatedMail() throws Exception {
        try (var f = fixture()) {
            String first = issue(f.email);
            request(f.email);
            verify(mail, times(1)).send(anyString(), anyString());
            owner().update("UPDATE password_reset_token SET created_at = created_at - interval '61 seconds' WHERE user_id = ?", f.userId);
            request(f.email);
            var tokens = ArgumentCaptor.forClass(String.class);
            verify(mail, times(2)).send(eq(f.email), tokens.capture());
            reset(first, NEW_PASSWORD).andExpect(status().isBadRequest());
            reset(tokens.getAllValues().getLast(), NEW_PASSWORD).andExpect(status().isNoContent());
        }
    }

    @Test
    void invalidPasswordDoesNotConsumeTokenAndMissingOrUnknownTokensFail() throws Exception {
        try (var f = fixture()) {
            String token = issue(f.email);
            for (String password : new String[]{"Abc1234", "abcdefgh1", "ABCDEFGH1", "Abcdefgh", "Aa1" + "á".repeat(40)}) {
                reset(token, password).andExpect(status().isBadRequest());
            }
            reset(secrets.generate(), NEW_PASSWORD).andExpect(status().isBadRequest());
            reset("", NEW_PASSWORD).andExpect(status().isBadRequest());
            reset(token, NEW_PASSWORD).andExpect(status().isNoContent());
        }
    }

    @Test
    void logoutRevokesCookieFamilyAndClearsCookieEvenWhenRepeatedOrAbsent() throws Exception {
        try (var f = fixture()) {
            var session = login(f.email, f.password);
            Cookie cookie = session.getResponse().getCookie("fixflow_refresh");
            for (int i = 0; i < 2; i++) {
                var response = mvc.perform(post("/api/v1/auth/logout").header("X-Auth-Request", "true").cookie(cookie))
                        .andExpect(status().isNoContent()).andExpect(header().string("Cache-Control", "no-store")).andReturn();
                String header = response.getResponse().getHeader("Set-Cookie");
                assertTrue(header.contains("Max-Age=0")); assertTrue(header.contains("HttpOnly"));
                assertTrue(header.contains("Secure")); assertTrue(header.contains("SameSite=Strict"));
                assertTrue(header.contains("Path=/api/v1/auth"));
            }
            mvc.perform(post("/api/v1/auth/refresh").header("X-Auth-Request", "true").cookie(cookie)).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/v1/auth/logout").header("X-Auth-Request", "true")).andExpect(status().isNoContent());
        }
    }

    @Test
    void csrfAndForeignOriginsAreRejectedForAllNewOperations() throws Exception {
        for (String path : new String[]{"forgot-password", "reset-password", "logout"}) {
            mvc.perform(post("/api/v1/auth/" + path).contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/auth/" + path).header("X-Auth-Request", "true").header("Origin", "https://other.invalid")
                    .contentType("application/json").content("{}")).andExpect(status().isForbidden());
        }
    }

    @Test
    void smtpFailureAndQueueSaturationDoNotDiscloseExistence() throws Exception {
        try (var f = fixture()) {
            doThrow(new IllegalStateException("Mail unavailable")).when(mail).send(anyString(), anyString());
            String expected = request("unknown@example.invalid").getResponse().getContentAsString();
            assertEquals(expected, request(f.email).getResponse().getContentAsString());
            doThrow(new TaskRejectedException("full")).when(executor).execute(any(Runnable.class));
            assertEquals(expected, request(f.email).getResponse().getContentAsString());
        }
    }

    private MembershipFixtures fixture() { return new MembershipFixtures(environment, encoder); }
    private JdbcTemplate owner() {
        String url = environment.getRequiredProperty("spring.flyway.url");
        if (!url.matches("jdbc:postgresql://[^/]+/fixflow_test(?:\\?.*)?")) throw new IllegalStateException("Test database required");
        return new JdbcTemplate(new DriverManagerDataSource(url, environment.getRequiredProperty("spring.flyway.user"), environment.getRequiredProperty("spring.flyway.password")));
    }
    private MvcResult request(String email) throws Exception {
        return mvc.perform(post("/api/v1/auth/forgot-password").header("X-Auth-Request", "true")
                .contentType("application/json").content(mapper.writeValueAsString(Map.of("email", email)))).andReturn();
    }
    private String issue(String email) {
        recovery.requestReset(email);
        var token = ArgumentCaptor.forClass(String.class); verify(mail).send(eq(email), token.capture()); return token.getValue();
    }
    private org.springframework.test.web.servlet.ResultActions reset(String token, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/reset-password").header("X-Auth-Request", "true")
                .contentType("application/json").content(mapper.writeValueAsString(Map.of("token", token, "newPassword", password))));
    }
    private MvcResult login(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").header("X-Auth-Request", "true")
                .contentType("application/json").content(mapper.writeValueAsString(Map.of("email", email, "password", password)))).andReturn();
    }
}
