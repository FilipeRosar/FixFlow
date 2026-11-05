package br.com.fixflow.modules.identity;

import br.com.fixflow.support.MembershipFixtures;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
@Import(UserManagementT5IntegrationTest.Probes.class)
class UserManagementT5IntegrationTest {
    @RestController
    static class Probes {
        @Autowired br.com.fixflow.modules.tenants.application.TenantLimitsService limits;
        @PostMapping("/api/oficinas/{tenant}/quota-probe")
        String quota(@PathVariable UUID tenant, @RequestParam(defaultValue = "false") boolean fail) {
            return limits.createWorkOrder(tenant, () -> {
                if (fail) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "rollback");
                return "ok";
            });
        }
        @GetMapping("/api/v1/dashboard/summary") String finance() { return "ok"; }
    }
    @Autowired MockMvc mvc;
    @Autowired Environment environment;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;
    MembershipFixtures fixture;
    final List<UUID> created = new ArrayList<>();
    String owner;
    String url;
    @BeforeEach void setup() throws Exception {
        fixture = new MembershipFixtures(environment, encoder);
        url = "/api/oficinas/" + fixture.tenantA + "/usuarios";
        owner = login(fixture.email, fixture.password);
    }
    @AfterEach void cleanup() {
        var ds = new DriverManagerDataSource(environment.getRequiredProperty("spring.flyway.url"),
                environment.getRequiredProperty("spring.flyway.user"), environment.getRequiredProperty("spring.flyway.password"));
        var jdbc = new JdbcTemplate(ds);
        new TransactionTemplate(new DataSourceTransactionManager(ds)).executeWithoutResult(tx -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, fixture.tenantA.toString());
            jdbc.update("DELETE FROM audit_log WHERE tenant_id = ?", fixture.tenantA);
        });
        fixture.close();
        for (var id : created) jdbc.update("DELETE FROM users WHERE id = ?", id);
    }
    String login(String email, String password) throws Exception {
        var response = mvc.perform(post("/api/v1/auth/login").header("X-Auth-Request", "true")
                .contentType("application/json").content(mapper.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(response.getResponse().getContentAsString()).get("accessToken").asText();
    }
    UUID create(String role) throws Exception {
        String email = UUID.randomUUID() + "@example.invalid";
        var result = mvc.perform(post(url).header("Authorization", "Bearer " + owner).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("fullName", "Equipe", "email", email, "password", "initial-password", "role", role))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value(role))
                .andExpect(jsonPath("$.password").doesNotExist()).andReturn();
        UUID id = UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("userId").asText());
        created.add(id);
        return id;
    }
    String tokenFor(UUID id) throws Exception {
        var response = mvc.perform(get(url).header("Authorization", "Bearer " + owner)).andExpect(status().isOk()).andReturn();
        for (var row : mapper.readTree(response.getResponse().getContentAsString()))
            if (row.get("userId").asText().equals(id.toString())) return login(row.get("email").asText(), "initial-password");
        throw new AssertionError("Missing user");
    }
    private void plan(String value) {
        var ds = new DriverManagerDataSource(environment.getRequiredProperty("spring.flyway.url"),
                environment.getRequiredProperty("spring.flyway.user"), environment.getRequiredProperty("spring.flyway.password"));
        var jdbc = new JdbcTemplate(ds);
        new TransactionTemplate(new DataSourceTransactionManager(ds)).executeWithoutResult(tx -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, fixture.tenantA.toString());
            jdbc.update("UPDATE tenants SET plan = ? WHERE id = ?", value, fixture.tenantA);
        });
    }
    @Test void orderQuotaRollsBackFailuresAndAllowsUpgrade() throws Exception {
        String endpoint = "/api/oficinas/" + fixture.tenantA + "/quota-probe";
        mvc.perform(post(endpoint + "?fail=true").header("Authorization", "Bearer " + owner))
                .andExpect(status().isBadRequest());
        for (int i = 0; i < 100; i++)
            mvc.perform(post(endpoint).header("Authorization", "Bearer " + owner)).andExpect(status().isOk());
        mvc.perform(post(endpoint).header("Authorization", "Bearer " + owner))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLAN_LIMIT_REACHED"));
        mvc.perform(get(url).header("Authorization", "Bearer " + owner)).andExpect(status().isOk());
        plan("PROFESSIONAL");
        mvc.perform(post(endpoint).header("Authorization", "Bearer " + owner)).andExpect(status().isOk());
    }
    @Test void limitsCreationButPreservesReadingAndDeactivation() throws Exception {
        UUID user = create("ATTENDANT");
        mvc.perform(post(url).header("Authorization", "Bearer " + owner).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("fullName", "Extra", "email", UUID.randomUUID() + "@example.invalid",
                        "password", "initial-password", "role", "TECHNICIAN"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLAN_LIMIT_REACHED"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("upgrade")));
        mvc.perform(get(url).header("Authorization", "Bearer " + owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(delete(url + "/" + user).header("Authorization", "Bearer " + owner)).andExpect(status().isNoContent());
        create("TECHNICIAN");
        plan("PROFESSIONAL");
        create("ATTENDANT");
        plan("STARTER");
        mvc.perform(get(url).header("Authorization", "Bearer " + owner)).andExpect(status().isOk());
    }
    @Test void t5DeniesTechnicianOrderCreationAndAttendantFinancialAccess() throws Exception {
        plan("PROFESSIONAL");
        var tech = tokenFor(create("TECHNICIAN"));
        var attendant = tokenFor(create("ATTENDANT"));
        mvc.perform(post("/api/v1/work-orders").header("Authorization", "Bearer " + tech)
                .contentType("application/json").content("{}")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/dashboard/summary").header("Authorization", "Bearer " + attendant))
                .andExpect(status().isForbidden());
        // Use the real OS endpoint: an authorized attendant reaches body validation.
        mvc.perform(post("/api/v1/work-orders").header("Authorization", "Bearer " + attendant)
                .contentType("application/json").content("{}")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.customerId").exists());
        mvc.perform(get("/api/v1/dashboard/summary").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk());
        mvc.perform(get(url).header("Authorization", "Bearer " + tech)).andExpect(status().isForbidden());
        mvc.perform(post(url).header("Authorization", "Bearer " + attendant).contentType("application/json")
                .content("{\"fullName\":\"X\",\"email\":\"x@example.invalid\",\"password\":\"initial-password\",\"role\":\"OWNER\"}"))
                .andExpect(status().isForbidden());
    }
    @Test void changesRoleAndDeactivatesImmediately() throws Exception {
        UUID user = create("ATTENDANT");
        String token = tokenFor(user);
        mvc.perform(patch(url + "/" + user + "/role").header("Authorization", "Bearer " + owner)
                .contentType("application/json").content("{\"role\":\"TECHNICIAN\"}")).andExpect(status().isNoContent());
        mvc.perform(get(url).header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        token = tokenFor(user);
        mvc.perform(delete(url + "/" + user).header("Authorization", "Bearer " + owner)).andExpect(status().isNoContent());
        mvc.perform(get(url).header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }
    @Test void serializesConcurrentOwnerDemotions() throws Exception {
        UUID second = create("OWNER");
        String secondToken = tokenFor(second);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return demote(fixture.userId, owner); });
            var other = pool.submit(() -> { start.await(); return demote(second, secondToken); });
            start.countDown();
            var statuses = new ArrayList<>(List.of(first.get(20, java.util.concurrent.TimeUnit.SECONDS),
                    other.get(20, java.util.concurrent.TimeUnit.SECONDS)));
            Collections.sort(statuses);
            org.junit.jupiter.api.Assertions.assertEquals(List.of(204, 409), statuses);
        }
    }
    int demote(UUID user, String token) throws Exception {
        return mvc.perform(patch(url + "/" + user + "/role").header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"role\":\"ATTENDANT\"}"))
                .andReturn().getResponse().getStatus();
    }
    @Test void rejectsDuplicateEmailAndOversizedUtf8Passwords() throws Exception {
        mvc.perform(post(url).header("Authorization", "Bearer " + owner).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("fullName", "Duplicado", "email", fixture.email.toUpperCase(Locale.ROOT),
                        "password", "initial-password", "role", "OWNER")))).andExpect(status().isConflict());
        mvc.perform(post(url).header("Authorization", "Bearer " + owner).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("fullName", "Equipe", "email", "utf8@example.invalid",
                        "password", "á".repeat(40), "role", "OWNER")))).andExpect(status().isBadRequest());
    }
    @Test void protectsLastOwnerAndOtherTenantAndValidatesRole() throws Exception {
        mvc.perform(delete(url + "/" + fixture.userId).header("Authorization", "Bearer " + owner)).andExpect(status().isConflict());
        mvc.perform(patch(url + "/" + fixture.userId + "/role").header("Authorization", "Bearer " + owner)
                .contentType("application/json").content("{\"role\":\"ADMIN\"}")).andExpect(status().isBadRequest());
        mvc.perform(delete(url + "/" + UUID.randomUUID()).header("Authorization", "Bearer " + owner)).andExpect(status().isNotFound());
        mvc.perform(get("/api/oficinas/" + fixture.tenantB + "/usuarios").header("Authorization", "Bearer " + owner)).andExpect(status().isForbidden());
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
    }
}
