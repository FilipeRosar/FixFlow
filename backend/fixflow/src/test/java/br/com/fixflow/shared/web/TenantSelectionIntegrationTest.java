package br.com.fixflow.shared.web;

import br.com.fixflow.shared.infrastructure.tenant.TenantContext;
import br.com.fixflow.support.MembershipFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
class TenantSelectionIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private Environment environment;
    @Autowired private PasswordEncoder encoder;
    private MembershipFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new MembershipFixtures(environment, encoder);
    }

    @AfterEach
    void tearDown() {
        try {
            assertTrue(TenantContext.getTenantId().isEmpty());
        } finally {
            TenantContext.clearTenantId();
            if (fixtures != null) fixtures.close();
        }
    }

    private String selection(UUID tenantId) {
        return "/api/oficinas/" + tenantId + "/selecao";
    }

    @Test
    void shouldRequireAuthentication() throws Exception {
        TenantContext.setTenantId(fixtures.tenantB);
        mvc.perform(get(selection(fixtures.tenantA)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("WWW-Authenticate"));
        assertTrue(TenantContext.getTenantId().isEmpty());
    }

    @Test
    void shouldAuthenticateRealIdentityAndSelectActiveMembership() throws Exception {
        mvc.perform(get(selection(fixtures.tenantA))
                        .with(httpBasic(fixtures.email, fixtures.password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(fixtures.userId.toString()))
                .andExpect(jsonPath("$.tenantId").value(fixtures.tenantA.toString()))
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.companyName").value("Integration workshop"));
    }

    @Test
    void shouldRejectInvalidPassword() throws Exception {
        mvc.perform(get(selection(fixtures.tenantA)).with(httpBasic(fixtures.email, "wrong")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectInvalidWorkshopIdentifier() throws Exception {
        mvc.perform(get("/api/oficinas/invalid/selecao").with(httpBasic(fixtures.email, fixtures.password)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldNotUseAnotherUsersMembershipFromClientInput() throws Exception {
        try (var other = new MembershipFixtures(environment, encoder)) {
            mvc.perform(get(selection(fixtures.tenantA))
                            .param("userId", fixtures.userId.toString())
                            .header("X-User-Id", fixtures.userId.toString())
                            .with(httpBasic(other.email, other.password)))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void shouldRejectSuspendedIdentity() throws Exception {
        fixtures.suspendUser();
        mvc.perform(get(selection(fixtures.tenantA)).with(httpBasic(fixtures.email, fixtures.password)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectSuspendedMembership() throws Exception {
        mvc.perform(get(selection(fixtures.tenantB)).with(httpBasic(fixtures.email, fixtures.password)))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldRejectSuspendedWorkshop() throws Exception {
        fixtures.suspendTenant();
        mvc.perform(get(selection(fixtures.tenantA)).with(httpBasic(fixtures.email, fixtures.password)))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldRejectUnknownWorkshopAndIgnoreClientIdentity() throws Exception {
        mvc.perform(get(selection(UUID.randomUUID()))
                        .param("userId", fixtures.userId.toString())
                        .header("X-User-Id", fixtures.userId.toString())
                        .with(httpBasic(fixtures.email, fixtures.password)))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldNotRememberSelectionOrAuthenticationOnNextRequest() throws Exception {
        mvc.perform(get(selection(fixtures.tenantA)).with(httpBasic(fixtures.email, fixtures.password)))
                .andExpect(status().isOk());
        mvc.perform(get(selection(fixtures.tenantA)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(selection(fixtures.tenantB)).with(httpBasic(fixtures.email, fixtures.password)))
                .andExpect(status().isForbidden());
    }
}
