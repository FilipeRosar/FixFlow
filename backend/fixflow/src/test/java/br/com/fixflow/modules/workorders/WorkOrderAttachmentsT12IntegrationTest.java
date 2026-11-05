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
class WorkOrderAttachmentsT12IntegrationTest {
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    br.com.fixflow.shared.application.audit.AuditService audit;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    br.com.fixflow.shared.application.files.FileStorage storage;
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
    @AfterEach void cleanup() {
        for (var tenant : List.of(fixture.tenantA, fixture.tenantB)) inTenant(tenant, () -> {
            admin.update("DELETE FROM outbox_event WHERE tenant_id = ?", tenant);
            admin.update("DELETE FROM inventory_movements WHERE tenant_id = ?", tenant);
            admin.update("DELETE FROM work_orders WHERE tenant_id = ?", tenant);
            admin.update("DELETE FROM parts WHERE tenant_id = ?", tenant);
            admin.update("DELETE FROM customers WHERE tenant_id = ?", tenant);
            admin.update("DELETE FROM audit_log WHERE tenant_id = ?", tenant);
        });
        fixture.close();
    }

    UUID order(UUID tenant) {
        UUID customer = UUID.randomUUID(), order = UUID.randomUUID();
        inTenant(tenant, () -> {
            admin.update("INSERT INTO customers(id, tenant_id, name) VALUES (?, ?, 'Cliente')", customer, tenant);
            admin.update("INSERT INTO work_orders(id, tenant_id, customer_id, number, status) VALUES (?, ?, ?, ?, 'RECEBIDA')", order, tenant, customer, 1 + Math.abs(order.getMostSignificantBits() % 100000000));
        });
        return order;
    }
    byte[] png() throws Exception {
        var output = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }
    org.springframework.test.web.servlet.ResultActions upload(UUID order, UUID uploadId, String filename, String type, byte[] bytes) throws Exception {
        var file = new org.springframework.mock.web.MockMultipartFile("file", filename, type, bytes);
        return mvc.perform(multipart(url + "/" + order + "/attachments").file(file).param("uploadId", uploadId.toString()).header("Authorization", "Bearer " + token));
    }
    UUID upload(UUID order) throws Exception {
        var response = upload(order, UUID.randomUUID(), "foto.png", "image/png", png()).andExpect(status().isCreated()).andReturn().getResponse();
        return UUID.fromString(mapper.readTree(response.getContentAsString()).get("id").asText());
    }
    void seed(UUID order, int first, int last) {
        inTenant(fixture.tenantA, () -> {
            for (int slot = first; slot <= last; slot++) {
                UUID id = UUID.randomUUID();
                admin.update("INSERT INTO work_order_attachments(id, tenant_id, work_order_id, upload_id, slot, storage_key, original_name, content_type, size_bytes, checksum, source_checksum, uploaded_by) VALUES (?, ?, ?, ?, ?, ?, 'foto.png', 'image/png', 1, ?, ?, ?)",
                        id, fixture.tenantA, order, UUID.randomUUID(), slot, "tenants/" + fixture.tenantA + "/work-orders/" + order + "/" + id + ".png", "a".repeat(64), "a".repeat(64), fixture.userId);
            }
        });
    }
    @Test void t12RejectsFalseMimeAndMagicBytesBeforeStorageAndPersistence() throws Exception {
        UUID order = order(fixture.tenantA);
        upload(order, UUID.randomUUID(), "foto.jpg", "image/jpeg", new byte[]{'M', 'Z', 0, 0}).andExpect(status().isUnprocessableContent());
        upload(order, UUID.randomUUID(), "foto.jpg", "image/jpeg", png()).andExpect(status().isUnprocessableContent());
        upload(order, UUID.randomUUID(), "foto.png", "application/pdf", png()).andExpect(status().isUnprocessableContent());
        org.mockito.Mockito.verifyNoInteractions(storage);
        inTenant(fixture.tenantA, () -> assertEquals(0, admin.queryForObject("SELECT count(*) FROM work_order_attachments WHERE work_order_id = ?", Integer.class, order)));
    }
    @Test void enforcesTenMiBLimitAndRejectsMissingFile() throws Exception {
        UUID order = order(fixture.tenantA);
        upload(order, UUID.randomUUID(), "foto.png", "image/png", new byte[10 * 1024 * 1024 + 1]).andExpect(status().isPayloadTooLarge());
        mvc.perform(multipart(url + "/" + order + "/attachments").header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest());
        org.mockito.Mockito.verifyNoInteractions(storage);
    }
    @Test void storesMetadataOnlyReplaysSafelyAndIssuesAuthorizedNoStoreDownload() throws Exception {
        UUID order = order(fixture.tenantA), uploadId = UUID.randomUUID();
        byte[] png = png();
        var response = upload(order, uploadId, "../../foto.png", "image/png", png).andExpect(status().isCreated())
                .andExpect(jsonPath("$.originalName").value("foto.png")).andExpect(jsonPath("$.storageKey").doesNotExist()).andReturn().getResponse();
        UUID id = UUID.fromString(mapper.readTree(response.getContentAsString()).get("id").asText());
        upload(order, uploadId, "foto.png", "image/png", png).andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(id.toString()));
        var key = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(storage).put(key.capture(), org.mockito.ArgumentMatchers.any(byte[].class), org.mockito.ArgumentMatchers.eq("image/png"), org.mockito.ArgumentMatchers.anyString());
        assertTrue(key.getValue().matches("tenants/" + fixture.tenantA + "/work-orders/" + order + "/[a-f0-9-]{36}\\.png"));
        org.mockito.Mockito.when(storage.download(key.getValue(), "image/png", "foto.png")).thenReturn(new br.com.fixflow.shared.application.files.FileStorage.Download("https://private.example/download?signature=test", java.time.Instant.now().plusSeconds(300)));
        mvc.perform(get("/api/v1/attachments/" + id + "/download-url").header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.expiresAt").exists());
        mvc.perform(get(url + "/" + order + "/attachments").header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        seed(order, 2, 20);
        upload(order, uploadId, "foto.png", "image/png", png).andExpect(status().isCreated());
        upload(order, UUID.randomUUID(), "foto.png", "image/png", png).andExpect(status().isConflict());
    }
    @Test void concurrentUploadsCannotExceedTwentyPerOrder() throws Exception {
        UUID order = order(fixture.tenantA); seed(order, 1, 19);
        byte[] bytes = png();
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 2; i++) futures.add(pool.submit(() -> {
                start.await();
                return upload(order, UUID.randomUUID(), "foto.png", "image/png", bytes).andReturn().getResponse().getStatus();
            }));
            start.countDown();
            var results = new ArrayList<Integer>();
            for (var result : futures) results.add(result.get(15, java.util.concurrent.TimeUnit.SECONDS));
            Collections.sort(results); assertEquals(List.of(201, 409), results);
            inTenant(fixture.tenantA, () -> assertEquals(20, admin.queryForObject("SELECT count(*) FROM work_order_attachments WHERE work_order_id = ?", Integer.class, order)));
            org.mockito.Mockito.verify(storage).put(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(byte[].class), org.mockito.ArgumentMatchers.eq("image/png"), org.mockito.ArgumentMatchers.anyString());
        } finally { pool.shutdownNow(); }
    }
    @Test void compensatesStorageWhenDatabaseAuditFailsAndDoesNotPublishMetadata() throws Exception {
        UUID order = order(fixture.tenantA);
        br.com.fixflow.shared.application.audit.AuditService target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(audit);
        org.mockito.Mockito.doThrow(new IllegalStateException("Simulated failure")).when(target).record(org.mockito.ArgumentMatchers.eq(fixture.userId), org.mockito.ArgumentMatchers.eq("WORK_ORDER_ATTACHMENT_UPLOADED"), org.mockito.ArgumentMatchers.eq("WORK_ORDER"), org.mockito.ArgumentMatchers.eq(order), org.mockito.ArgumentMatchers.anyString());
        upload(order, UUID.randomUUID(), "foto.png", "image/png", png()).andExpect(status().isInternalServerError());
        var key = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(storage).put(key.capture(), org.mockito.ArgumentMatchers.any(byte[].class), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verify(storage).delete(key.getValue());
        inTenant(fixture.tenantA, () -> assertEquals(0, admin.queryForObject("SELECT count(*) FROM work_order_attachments WHERE work_order_id = ?", Integer.class, order)));
    }
    @Test void compensatesAmbiguousStorageFailureAndLeavesNoMetadata() throws Exception {
        UUID order = order(fixture.tenantA);
        org.mockito.Mockito.doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)).when(storage).put(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(byte[].class), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        upload(order, UUID.randomUUID(), "foto.png", "image/png", png()).andExpect(status().isServiceUnavailable());
        org.mockito.Mockito.verify(storage).delete(org.mockito.ArgumentMatchers.anyString());
        inTenant(fixture.tenantA, () -> assertEquals(0, admin.queryForObject("SELECT count(*) FROM work_order_attachments WHERE work_order_id = ?", Integer.class, order)));
    }
    @Test void restrictsTenantAndTechnicianAssignmentWhileAllowingAttendantUploads() throws Exception {
        UUID order = order(fixture.tenantA), foreign = order(fixture.tenantB), id = upload(order);
        mvc.perform(get(url + "/" + foreign + "/attachments").header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        upload(foreign, UUID.randomUUID(), "foto.png", "image/png", png()).andExpect(status().isNotFound());
        inTenant(fixture.tenantA, () -> admin.update("UPDATE tenant_memberships SET role = 'TECHNICIAN' WHERE tenant_id = ? AND user_id = ?", fixture.tenantA, fixture.userId)); token = login();
        mvc.perform(get("/api/v1/attachments/" + id + "/download-url").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        upload(order, UUID.randomUUID(), "foto.png", "image/png", png()).andExpect(status().isForbidden());
        inTenant(fixture.tenantA, () -> admin.update("INSERT INTO work_order_transition_facts(tenant_id, work_order_id, technician_id) VALUES (?, ?, ?)", fixture.tenantA, order, fixture.userId));
        upload(order, UUID.randomUUID(), "foto.png", "image/png", png()).andExpect(status().isCreated());
        inTenant(fixture.tenantA, () -> admin.update("UPDATE tenant_memberships SET role = 'ATTENDANT' WHERE tenant_id = ? AND user_id = ?", fixture.tenantA, fixture.userId)); token = login();
        upload(order, UUID.randomUUID(), "foto.png", "image/png", png()).andExpect(status().isCreated());
    }
}
