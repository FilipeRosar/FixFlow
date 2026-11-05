package br.com.fixflow.modules.workorders;

import br.com.fixflow.support.MembershipFixtures;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
class WorkOrderTimelineIntegrationTest {
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    br.com.fixflow.shared.application.audit.AuditService audit;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    br.com.fixflow.shared.application.files.FileStorage storage;
    @Autowired JdbcTemplate runtimeJdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired Environment environment;
    @Autowired PasswordEncoder encoder;
    MembershipFixtures fixture;
    JdbcTemplate admin;
    TransactionTemplate transaction;
    String token;
    final String url = "/api/v1/work-orders";
    @BeforeEach void setup() throws Exception {
        fixture = new MembershipFixtures(environment, encoder);
        var ds = new DriverManagerDataSource(environment.getRequiredProperty("spring.flyway.url"),
                environment.getRequiredProperty("spring.flyway.user"), environment.getRequiredProperty("spring.flyway.password"));
        admin = new JdbcTemplate(ds);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
        token = login();
    }
    String login() throws Exception {
        var result = mvc.perform(post("/api/v1/auth/login").header("X-Auth-Request", "true").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("email", fixture.email, "password", fixture.password))))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }
    void inTenant(UUID tenant, Runnable action) {
        transaction.executeWithoutResult(tx -> {
            admin.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenant.toString());
            action.run();
        });
    }
    final List<UUID> extraUsers = new ArrayList<>();
    @AfterEach void cleanup() {
        for (var tenant : List.of(fixture.tenantA, fixture.tenantB)) inTenant(tenant, () -> {
            admin.update("DELETE FROM outbox_event WHERE tenant_id = ?", tenant);
            admin.update("DELETE FROM work_orders WHERE tenant_id = ?", tenant);
            admin.update("DELETE FROM customers WHERE tenant_id = ?", tenant);
            admin.update("DELETE FROM audit_log WHERE tenant_id = ?", tenant);
        });
        fixture.close();
        for (UUID user : extraUsers) admin.update("DELETE FROM users WHERE id = ?", user);
    }
    UUID customer(UUID tenant, String name) {
        UUID id = UUID.randomUUID();
        inTenant(tenant, () -> admin.update("INSERT INTO customers(id, tenant_id, name) VALUES (?, ?, ?)", id, tenant, name));
        return id;
    }
    UUID equipment(UUID tenant, UUID customer) {
        UUID id = UUID.randomUUID();
        inTenant(tenant, () -> admin.update("INSERT INTO equipment(id, tenant_id, customer_id, category, model) VALUES (?, ?, ?, 'NOTEBOOK', 'Dell')", id, tenant, customer));
        return id;
    }
    Map<String, Object> body(UUID customer, UUID equipment) {
        return Map.of("customerId", customer, "equipmentId", equipment, "reportedIssue", "Não liga",
                "receivedAccessories", List.of("Carregador"), "physicalCondition", "Risco na tampa");
    }
    UUID open(UUID customer, UUID equipment, long number) throws Exception {
        var result = mvc.perform(post(url).header("Authorization", "Bearer " + token).contentType("application/json")
                .content(mapper.writeValueAsString(body(customer, equipment))))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.number").value(number)).andExpect(jsonPath("$.status").value("RECEBIDA"))
                .andExpect(jsonPath("$.receivedAccessories[0]").value("Carregador"))
                .andExpect(jsonPath("$.version").value(0)).andReturn();
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }
    UUID order() throws Exception {
        UUID customer = customer(fixture.tenantA, "Ana");
        return open(customer, equipment(fixture.tenantA, customer), 1);
    }
    UUID member(UUID tenant, String role, String membershipStatus, String userStatus) {
        UUID id = UUID.randomUUID(); extraUsers.add(id);
        admin.update("INSERT INTO users(id, full_name, email, password_hash, status) VALUES (?, 'Técnico de teste', ?, 'unused-hash', ?)", id, id + "@example.invalid", userStatus);
        inTenant(tenant, () -> admin.update("INSERT INTO tenant_memberships(user_id, tenant_id, role, status) VALUES (?, ?, ?, ?)", id, tenant, role, membershipStatus));
        return id;
    }
    org.springframework.test.web.servlet.ResultActions assign(UUID order, UUID technician, int version) throws Exception {
        return mvc.perform(patch(url + "/" + order + "/technician").header("Authorization", "Bearer " + token).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("technicianId", technician, "version", version))));
    }
    UUID diagnosingOrder() throws Exception {
        UUID id = order(); assign(id, fixture.userId, 0).andExpect(status().isOk());
        transition(id, "EM_DIAGNOSTICO", 1).andExpect(status().isOk()); return id;
    }
    org.springframework.test.web.servlet.ResultActions transition(UUID id, String target, int version) throws Exception {
        return mvc.perform(patch(url + "/" + id + "/status").header("Authorization", "Bearer " + token).contentType("application/json").content(mapper.writeValueAsString(Map.of("toStatus", target, "version", version))));
    }
    org.springframework.test.web.servlet.ResultActions record(UUID id, int version, String findings) throws Exception {
        return mvc.perform(post(url + "/" + id + "/diagnosis").header("Authorization", "Bearer " + token).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("version", version, "findings", findings, "probableCause", "Fonte danificada", "proposedSolution", "Trocar fonte"))));
    }
    void role(String role) throws Exception {
        inTenant(fixture.tenantA, () -> admin.update("UPDATE tenant_memberships SET role = ? WHERE tenant_id = ? AND user_id = ?", role, fixture.tenantA, fixture.userId)); token = login();
    }

    org.springframework.test.web.servlet.ResultActions timeline(UUID order, int page, int size) throws Exception {
        return mvc.perform(get(url + "/" + order + "/timeline").param("page", "" + page).param("size", "" + size).header("Authorization", "Bearer " + token));
    }
    UUID progressed() throws Exception {
        UUID id = diagnosingOrder();
        record(id, 2, "Fonte em curto").andExpect(status().isCreated());
        mvc.perform(patch(url + "/" + id + "/status").header("Authorization", "Bearer " + token).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("toStatus", "AGUARDANDO_ORCAMENTO", "version", 3, "reason", "  Diagnóstico concluído  ")))).andExpect(status().isOk());
        return id;
    }
    @Test void showsTransitionsAndBusinessEventsWithAuthorTimestampReasonWithoutDuplicates() throws Exception {
        UUID id = progressed();
        timeline(id, 0, 20).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.content[0].eventType").value("WORK_ORDER_STATUS_CHANGED"))
                .andExpect(jsonPath("$.content[0].fromStatus").value("EM_DIAGNOSTICO"))
                .andExpect(jsonPath("$.content[0].toStatus").value("AGUARDANDO_ORCAMENTO"))
                .andExpect(jsonPath("$.content[0].reason").value("Diagnóstico concluído"))
                .andExpect(jsonPath("$.content[0].actorId").value(fixture.userId.toString()))
                .andExpect(jsonPath("$.content[0].actorName").value("Integration identity"))
                .andExpect(jsonPath("$.content[0].occurredAt").exists())
                .andExpect(jsonPath("$.content[1].eventType").value("WORK_ORDER_DIAGNOSIS_RECORDED"))
                .andExpect(jsonPath("$.content[1].details.revision").value(1))
                .andExpect(jsonPath("$.content[3].details.technicianName").value("Integration identity"))
                .andExpect(jsonPath("$.content[4].eventType").value("WORK_ORDER_CREATED"));
    }
    @Test void actorAndAssignedTechnicianNamesRemainImmutableAfterAccountRename() throws Exception {
        UUID id = diagnosingOrder();
        String before = timeline(id, 0, 20).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        admin.update("UPDATE users SET full_name = 'Nome novo' WHERE id = ?", fixture.userId);
        String after = timeline(id, 0, 20).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(before, after);
        assertFalse(after.contains("Nome novo"));
    }
    @Test void enforcesAppendOnlyStorageAndHasNoMutationEndpoint() throws Exception {
        UUID id = progressed();
        assertFalse(admin.queryForObject("SELECT has_table_privilege('fixflow_runtime', 'public.work_order_timeline', 'INSERT')", Boolean.class));
        assertFalse(admin.queryForObject("SELECT has_table_privilege('fixflow_runtime', 'public.work_order_timeline', 'UPDATE')", Boolean.class));
        assertFalse(admin.queryForObject("SELECT has_table_privilege('fixflow_runtime', 'public.work_order_timeline', 'DELETE')", Boolean.class));
        var runtimeTx = new TransactionTemplate(new DataSourceTransactionManager(runtimeJdbc.getDataSource()));
        for (String sql : List.of("UPDATE public.work_order_timeline SET actor_name = 'Alterado' WHERE work_order_id = ?", "DELETE FROM public.work_order_timeline WHERE work_order_id = ?")) {
            runtimeTx.executeWithoutResult(tx -> {
                runtimeJdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, fixture.tenantA.toString());
                assertThrows(org.springframework.dao.DataAccessException.class, () -> runtimeJdbc.update(sql, id));
                tx.setRollbackOnly();
            });
        }
        mvc.perform(delete(url + "/" + id + "/timeline").header("Authorization", "Bearer " + token)).andExpect(status().isMethodNotAllowed());
        timeline(id, 0, 20).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(5));
    }
    @Test void permitsAttendantAndAssignedTechnicianButDoesNotLeakAnotherTenantOrAssignment() throws Exception {
        UUID id = diagnosingOrder();
        role("ATTENDANT"); timeline(id, 0, 20).andExpect(status().isOk());
        role("TECHNICIAN"); timeline(id, 0, 20).andExpect(status().isOk());
        role("OWNER");
        UUID colleague = member(fixture.tenantA, "TECHNICIAN", "ACTIVE", "ACTIVE");
        assign(id, colleague, 2).andExpect(status().isOk());
        role("TECHNICIAN"); timeline(id, 0, 20).andExpect(status().isForbidden());
        UUID foreignCustomer = customer(fixture.tenantB, "Outro cliente"), foreignOrder = UUID.randomUUID();
        inTenant(fixture.tenantB, () -> admin.update("INSERT INTO work_orders(id, tenant_id, customer_id, number, status) VALUES (?, ?, ?, 1, 'RECEBIDA')", foreignOrder, fixture.tenantB, foreignCustomer));
        timeline(foreignOrder, 0, 20).andExpect(status().isNotFound());
    }
    @Test void rollsBackTimelineTogetherWithTransitionWhenAuditFails() throws Exception {
        UUID id = order(); assign(id, fixture.userId, 0).andExpect(status().isOk());
        br.com.fixflow.shared.application.audit.AuditService target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(audit);
        org.mockito.Mockito.doThrow(new IllegalStateException("Failure after history insertion")).when(target).record(
                org.mockito.ArgumentMatchers.eq(fixture.userId), org.mockito.ArgumentMatchers.eq("WORK_ORDER_STATUS_CHANGED"),
                org.mockito.ArgumentMatchers.eq("WORK_ORDER"), org.mockito.ArgumentMatchers.eq(id), org.mockito.ArgumentMatchers.anyString());
        transition(id, "EM_DIAGNOSTICO", 1).andExpect(status().isInternalServerError());
        timeline(id, 0, 20).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
        inTenant(fixture.tenantA, () -> assertEquals(1, admin.queryForObject("SELECT count(*) FROM work_order_status_history WHERE work_order_id = ?", Integer.class, id)));
    }
    @Test void sanitizesAuditPayloadAndDoesNotTurnAccessLogsIntoBusinessEvents() throws Exception {
        UUID id = order();
        inTenant(fixture.tenantA, () -> {
            admin.update("INSERT INTO audit_log(tenant_id, actor_id, action, resource_type, resource_id, details) VALUES (?, ?, 'WORK_ORDER_DIAGNOSIS_RECORDED', 'WORK_ORDER', ?, ?::jsonb)",
                    fixture.tenantA, fixture.userId, id, "{\"revision\":3,\"publicToken\":\"secret-value\",\"storageKey\":\"private-key\"}");
            admin.update("INSERT INTO audit_log(tenant_id, actor_id, action, resource_type, resource_id, details) VALUES (?, ?, 'WORK_ORDER_ATTACHMENT_DOWNLOAD_URL_ISSUED', 'WORK_ORDER', ?, '{}'::jsonb)", fixture.tenantA, fixture.userId, id);
        });
        var response = timeline(id, 0, 20).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].details.revision").value(3)).andReturn().getResponse().getContentAsString();
        assertFalse(response.contains("secret-value")); assertFalse(response.contains("private-key")); assertFalse(response.contains("publicToken"));
    }
    @Test void capturesItemRemovalAndAttachmentsAsImmutableBusinessEvents() throws Exception {
        UUID id = diagnosingOrder(), item = UUID.randomUUID();
        inTenant(fixture.tenantA, () -> admin.update("UPDATE work_orders SET status = 'EM_MANUTENCAO' WHERE id = ?", id));
        mvc.perform(post(url + "/" + id + "/items").header("Authorization", "Bearer " + token).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("id", item, "version", 2, "type", "SERVICE", "description", "Limpeza técnica", "quantity", 1, "unitPrice", 50))))
                .andExpect(status().isCreated());
        mvc.perform(delete(url + "/" + id + "/items/" + item).param("version", "3").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        inTenant(fixture.tenantA, () -> admin.update("UPDATE work_order_items SET description = 'Nome posterior' WHERE id = ?", item));
        var output = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", output);
        mvc.perform(multipart(url + "/" + id + "/attachments").file(new org.springframework.mock.web.MockMultipartFile("file", "entrada.png", "image/png", output.toByteArray()))
                .header("Authorization", "Bearer " + token)).andExpect(status().isCreated());
        timeline(id, 0, 20).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.content[0].eventType").value("WORK_ORDER_ATTACHMENT_UPLOADED"))
                .andExpect(jsonPath("$.content[0].details.filename").value("entrada.png"))
                .andExpect(jsonPath("$.content[1].eventType").value("WORK_ORDER_ITEM_REMOVED"))
                .andExpect(jsonPath("$.content[1].details.description").value("Limpeza técnica"))
                .andExpect(jsonPath("$.content[2].details.description").value("Limpeza técnica"));
    }
    @Test void paginatesDeterministicallyAndRejectsInvalidBounds() throws Exception {
        UUID id = progressed();
        var first = timeline(id, 0, 2).andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(2)).andReturn().getResponse().getContentAsString();
        var second = timeline(id, 1, 2).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(5)).andReturn().getResponse().getContentAsString();
        assertNotEquals(mapper.readTree(first).get("content").get(0).get("id").asText(), mapper.readTree(second).get("content").get(0).get("id").asText());
        timeline(id, 2, 2).andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1));
        timeline(id, -1, 20).andExpect(status().isBadRequest());
        timeline(id, 0, 101).andExpect(status().isBadRequest());
    }
}
