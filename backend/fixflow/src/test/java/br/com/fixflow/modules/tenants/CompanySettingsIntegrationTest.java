package br.com.fixflow.modules.tenants;

import br.com.fixflow.modules.tenants.application.TenantPolicies;
import br.com.fixflow.modules.tenants.domain.InsufficientStockException;
import br.com.fixflow.shared.infrastructure.security.AuthenticatedIdentity;
import br.com.fixflow.support.MembershipFixtures;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
class CompanySettingsIntegrationTest {
    @org.junit.jupiter.api.io.TempDir static java.nio.file.Path storage;
    @org.springframework.test.context.DynamicPropertySource
    static void storageProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("fixflow.files.root", () -> storage.toString());
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired Environment environment;
    @Autowired PasswordEncoder encoder;
    @Autowired TenantPolicies policies;
    MembershipFixtures fixture;
    JdbcTemplate admin;
    TransactionTemplate transaction;
    String owner;
    @BeforeEach void setup() throws Exception {
        fixture = new MembershipFixtures(environment, encoder);
        var ds = new DriverManagerDataSource(environment.getRequiredProperty("spring.flyway.url"),
                environment.getRequiredProperty("spring.flyway.user"), environment.getRequiredProperty("spring.flyway.password"));
        admin = new JdbcTemplate(ds);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
        owner = login(fixture);
    }
    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        inTenant(() -> admin.update("DELETE FROM audit_log WHERE tenant_id = ?", fixture.tenantA));
        fixture.close();
    }
    void inTenant(Runnable action) {
        transaction.executeWithoutResult(tx -> {
            admin.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, fixture.tenantA.toString());
            action.run();
        });
    }
    String login(MembershipFixtures f) throws Exception {
        var response = mvc.perform(post("/api/v1/auth/login").header("X-Auth-Request", "true").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("email", f.email, "password", f.password))))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(response.getResponse().getContentAsString()).get("accessToken").asText();
    }
    Map<String, Object> settings(String logo, String terms, int days, boolean negative) {
        var data = new HashMap<String, Object>();
        data.put("logoDataUrl", logo); data.put("warrantyTerms", terms);
        data.put("quoteValidityDays", days); data.put("allowNegativeStock", negative);
        return data;
    }
    void save(Map<String, Object> body) throws Exception {
        mvc.perform(put("/api/v1/tenant").header("Authorization", "Bearer " + owner)
                .contentType("application/json").content(mapper.writeValueAsString(body))).andExpect(status().isOk());
    }
    void authenticatePolicyCaller() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new AuthenticatedIdentity(fixture.userId, fixture.email, fixture.tenantA), null, List.of()));
    }
    @Test void defaultsPersistAndApplyWithoutChangingExistingQuoteSnapshots() throws Exception {
        mvc.perform(get("/api/v1/tenant").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.quoteValidityDays").value(7))
                .andExpect(jsonPath("$.allowNegativeStock").value(false)).andExpect(jsonPath("$.warrantyTerms").value(""));
        Instant now = Instant.parse("2026-11-04T12:00:00Z");
        authenticatePolicyCaller();
        var initial = policies.quoteDefaults(fixture.tenantA, now);
        assertEquals(now.plus(7, ChronoUnit.DAYS), initial.validUntil());
        assertThrows(InsufficientStockException.class, () -> policies.remainingStock(fixture.tenantA, BigDecimal.ONE, BigDecimal.TEN));
        SecurityContextHolder.clearContext();
        save(settings(null, "Garantia conforme condições do serviço.\nPreserve o comprovante.", 14, true));
        mvc.perform(get("/api/v1/tenant").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.quoteValidityDays").value(14))
                .andExpect(jsonPath("$.allowNegativeStock").value(true));
        authenticatePolicyCaller();
        var updated = policies.quoteDefaults(fixture.tenantA, now);
        assertEquals(now.plus(14, ChronoUnit.DAYS), updated.validUntil());
        assertTrue(updated.warrantyTerms().contains("Preserve"));
        assertEquals(now.plus(7, ChronoUnit.DAYS), initial.validUntil());
        assertEquals("", initial.warrantyTerms());
        assertEquals(new BigDecimal("-9"), policies.remainingStock(fixture.tenantA, BigDecimal.ONE, BigDecimal.TEN));
        assertThrows(IllegalArgumentException.class, () -> policies.remainingStock(fixture.tenantA, BigDecimal.ONE, BigDecimal.ZERO));
        SecurityContextHolder.clearContext();
        save(settings(null, "", 7, false));
        authenticatePolicyCaller();
        assertThrows(InsufficientStockException.class, () -> policies.remainingStock(fixture.tenantA, BigDecimal.ONE, BigDecimal.TEN));
        inTenant(() -> assertEquals(2, admin.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'TENANT_SETTINGS_UPDATED'", Integer.class, fixture.tenantA)));
    }
    @Test void isolatesSettingsAndCannotSelectAnotherTenantByHeaderOrQuery() throws Exception {
        save(settings(null, "Somente empresa A", 30, true));
        try (var other = new MembershipFixtures(environment, encoder)) {
            String otherToken = login(other);
            mvc.perform(get("/api/v1/tenant").param("tenantId", fixture.tenantA.toString()).header("X-Tenant-Id", fixture.tenantA)
                    .header("Authorization", "Bearer " + otherToken)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.quoteValidityDays").value(7)).andExpect(jsonPath("$.allowNegativeStock").value(false));
            authenticatePolicyCaller();
            assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> policies.quoteDefaults(other.tenantA, Instant.now()));
            SecurityContextHolder.clearContext();
        }
    }
    @Test void ownerOnlyAndSuspensionTakesEffectImmediately() throws Exception {
        mvc.perform(get("/api/v1/tenant")).andExpect(status().isUnauthorized());
        for (String role : List.of("ATTENDANT", "TECHNICIAN")) {
            inTenant(() -> admin.update("UPDATE tenant_memberships SET role = ? WHERE user_id = ? AND tenant_id = ?", role, fixture.userId, fixture.tenantA));
            String token = login(fixture);
            mvc.perform(get("/api/v1/tenant").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
            mvc.perform(put("/api/v1/tenant").header("Authorization", "Bearer " + token).contentType("application/json")
                    .content(mapper.writeValueAsString(settings(null, "", 7, false)))).andExpect(status().isForbidden());
        }
        inTenant(() -> admin.update("UPDATE tenant_memberships SET role = 'OWNER' WHERE user_id = ? AND tenant_id = ?", fixture.userId, fixture.tenantA));
        fixture.suspendUser();
        mvc.perform(get("/api/v1/tenant").header("Authorization", "Bearer " + owner)).andExpect(status().isUnauthorized());
    }
    @Test void logoIsValidatedShownInSelectionAndCanBeRemoved() throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", bytes);
        String logo = "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray());
        save(settings(logo, "Garantia", 7, false));
        mvc.perform(get("/api/oficinas/" + fixture.tenantA + "/selecao").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.logoDataUrl").value(logo));
        for (String bad : List.of("data:image/svg+xml;base64,PHN2Zz4=", "https://example.com/logo.png", "data:image/png;base64,AAAA")) {
            mvc.perform(put("/api/v1/tenant").header("Authorization", "Bearer " + owner).contentType("application/json")
                    .content(mapper.writeValueAsString(settings(bad, "", 7, false)))).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/tenant").header("Authorization", "Bearer " + owner)).andExpect(jsonPath("$.logoDataUrl").value(logo));
        save(settings(null, "", 7, false));
        mvc.perform(get("/api/oficinas/" + fixture.tenantA + "/selecao").header("Authorization", "Bearer " + owner))
                .andExpect(jsonPath("$.logoDataUrl").doesNotExist());
    }
    @Test void auditFailureRollsBackSettingsAndRemovesNewlyUploadedFile() throws Exception {
        save(settings(null, "Antes da falha", 14, false));
        long countBefore;
        try (var paths = java.nio.file.Files.walk(storage)) { countBefore = paths.filter(java.nio.file.Files::isRegularFile).count(); }
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", bytes);
        String logo = "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray());
        String constraint = "fail_audit_" + fixture.userId.toString().replace("-", "");
        admin.execute("ALTER TABLE public.audit_log ADD CONSTRAINT " + constraint
                + " CHECK (tenant_id <> '" + fixture.tenantA + "'::uuid) NOT VALID");
        try {
        mvc.perform(put("/api/v1/tenant").header("Authorization", "Bearer " + owner).contentType("application/json")
                .content(mapper.writeValueAsString(settings(logo, "Não deve persistir", 30, true)))).andExpect(status().isInternalServerError());
        mvc.perform(get("/api/v1/tenant").header("Authorization", "Bearer " + owner))
                .andExpect(jsonPath("$.quoteValidityDays").value(14)).andExpect(jsonPath("$.warrantyTerms").value("Antes da falha"))
                .andExpect(jsonPath("$.logoDataUrl").doesNotExist());
        try (var paths = java.nio.file.Files.walk(storage)) { assertEquals(countBefore, paths.filter(java.nio.file.Files::isRegularFile).count()); }
        } finally { admin.execute("ALTER TABLE public.audit_log DROP CONSTRAINT " + constraint); }
    }
    @Test void validatesBoundsRequiredFieldsAndUtf8TermsWithoutPartialSave() throws Exception {
        save(settings(null, "Condições originais", 14, false));
        for (var invalid : List.of(settings(null, "", 0, false), settings(null, "", 366, true), settings(null, "x".repeat(10001), 7, true))) {
            mvc.perform(put("/api/v1/tenant").header("Authorization", "Bearer " + owner).contentType("application/json")
                    .content(mapper.writeValueAsString(invalid))).andExpect(status().isBadRequest());
        }
        mvc.perform(put("/api/v1/tenant").header("Authorization", "Bearer " + owner).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/tenant").header("Authorization", "Bearer " + owner)).andExpect(jsonPath("$.quoteValidityDays").value(14))
                .andExpect(jsonPath("$.warrantyTerms").value("Condições originais"));
    }
}
