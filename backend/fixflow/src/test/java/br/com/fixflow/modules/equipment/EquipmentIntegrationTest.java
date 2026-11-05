package br.com.fixflow.modules.equipment;

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
class EquipmentIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired Environment environment;
    @Autowired PasswordEncoder encoder;
    MembershipFixtures fixture;
    JdbcTemplate admin;
    TransactionTemplate transaction;
    String token;
    final String url = "/api/v1/equipment";
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
            admin.update("DELETE FROM work_orders WHERE tenant_id = ?", tenant);
            admin.update("DELETE FROM customers WHERE tenant_id = ?", tenant);
            admin.update("DELETE FROM audit_log WHERE tenant_id = ?", tenant);
        });
        fixture.close();
    }
    UUID customer(UUID tenant, String name) {
        UUID id = UUID.randomUUID();
        inTenant(tenant, () -> admin.update("INSERT INTO customers(id, tenant_id, name) VALUES (?, ?, ?)", id, tenant, name));
        return id;
    }
    Map<String, Object> body(UUID customer, String serial) {
        return Map.of("customerId", customer, "category", "NOTEBOOK", "brand", "Dell", "model", "Inspiron", "serialNumber", serial, "description", "Notebook prateado");
    }
    UUID create(UUID customer, String serial) throws Exception {
        var response = mvc.perform(post(url).header("Authorization", "Bearer " + token).contentType("application/json")
                .content(mapper.writeValueAsString(body(customer, serial))))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.customerId").value(customer.toString())).andReturn();
        return UUID.fromString(mapper.readTree(response.getResponse().getContentAsString()).get("id").asText());
    }
    @Test void crudSerialSearchPaginationAndCustomerFilter() throws Exception {
        UUID customer = customer(fixture.tenantA, "Ana");
        UUID other = customer(fixture.tenantA, "Bruno");
        UUID id = create(customer, "ABC-123");
        create(other, "XYZ-456");
        mvc.perform(get(url).param("serial", "  abc-1  ").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id.toString()));
        mvc.perform(get("/api/v1/customers/" + customer + "/equipment").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get(url).param("size", "1").param("page", "1").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.content.length()").value(1));
        mvc.perform(put(url + "/" + id).header("Authorization", "Bearer " + token).contentType("application/json")
                .content(mapper.writeValueAsString(body(other, "NEW-SERIAL"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.customerName").value("Bruno"));
        mvc.perform(delete(url + "/" + id).header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());
        mvc.perform(get(url + "/" + id).header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        mvc.perform(get(url).param("serial", "NEW-SERIAL").header("Authorization", "Bearer " + token)).andExpect(jsonPath("$.totalElements").value(0));
        inTenant(fixture.tenantA, () -> assertEquals(1, admin.queryForObject("SELECT count(*) FROM equipment WHERE id = ? AND deleted_at IS NOT NULL", Integer.class, id)));
    }
    @Test void rejectsForeignMissingAndDeletedCustomerOnCreateAndTransfer() throws Exception {
        UUID own = customer(fixture.tenantA, "Local");
        UUID foreign = customer(fixture.tenantB, "Outra empresa");
        UUID deleted = customer(fixture.tenantA, "Excluído");
        inTenant(fixture.tenantA, () -> admin.update("UPDATE customers SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", deleted));
        UUID id = create(own, "SAFE");
        for (var invalid : List.of(foreign, deleted, UUID.randomUUID())) {
            mvc.perform(post(url).header("Authorization", "Bearer " + token).contentType("application/json").content(mapper.writeValueAsString(body(invalid, "BAD"))))
                    .andExpect(status().isNotFound());
            mvc.perform(put(url + "/" + id).header("Authorization", "Bearer " + token).contentType("application/json").content(mapper.writeValueAsString(body(invalid, "BAD"))))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(get(url + "/" + id).header("Authorization", "Bearer " + token)).andExpect(jsonPath("$.customerId").value(own.toString()));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> inTenant(fixture.tenantA,
                () -> admin.update("INSERT INTO equipment(tenant_id, customer_id, category, model) VALUES (?, ?, 'NOTEBOOK', 'Forbidden')", fixture.tenantA, foreign)));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> inTenant(fixture.tenantA,
                () -> admin.update("UPDATE equipment SET customer_id = ? WHERE id = ?", foreign, id)));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> inTenant(fixture.tenantA,
                () -> admin.update("INSERT INTO equipment(tenant_id, customer_id, category, model) VALUES (?, ?, 'NOTEBOOK', 'Forbidden')", fixture.tenantA, deleted)));
    }
    @Test void keepsTenantIsolationAcrossAllOperations() throws Exception {
        UUID customer = customer(fixture.tenantB, "Estrangeiro");
        UUID id = UUID.randomUUID();
        inTenant(fixture.tenantB, () -> admin.update("INSERT INTO equipment(id, tenant_id, customer_id, category, model, serial_number) VALUES (?, ?, ?, 'NOTEBOOK', 'Foreign', 'SECRET')", id, fixture.tenantB, customer));
        mvc.perform(get(url).param("serial", "SECRET").header("Authorization", "Bearer " + token)).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get(url + "/" + id).header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        mvc.perform(delete(url + "/" + id).header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        mvc.perform(put(url + "/" + id).header("Authorization", "Bearer " + token).contentType("application/json").content(mapper.writeValueAsString(body(customer, "BAD"))))
                .andExpect(status().isNotFound());
        mvc.perform(patch(url + "/" + id + "/description").header("Authorization", "Bearer " + token).contentType("application/json").content(mapper.writeValueAsString(Map.of("description", "BAD"))))
                .andExpect(status().isNotFound());
    }
    @Test void technicianCanOnlyReadAndChangeDescriptionAndAttendantCanCreate() throws Exception {
        UUID customer = customer(fixture.tenantA, "Cliente");
        UUID id = create(customer, "SERIAL");
        inTenant(fixture.tenantA, () -> admin.update("UPDATE tenant_memberships SET role = 'TECHNICIAN' WHERE user_id = ? AND tenant_id = ?", fixture.userId, fixture.tenantA));
        token = login();
        mvc.perform(get(url).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(post(url).header("Authorization", "Bearer " + token).contentType("application/json").content(mapper.writeValueAsString(body(customer, "NO")))).andExpect(status().isForbidden());
        mvc.perform(put(url + "/" + id).header("Authorization", "Bearer " + token).contentType("application/json").content(mapper.writeValueAsString(body(customer, "NO")))).andExpect(status().isForbidden());
        mvc.perform(delete(url + "/" + id).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mvc.perform(patch(url + "/" + id + "/description").header("Authorization", "Bearer " + token).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("description", "Descrição revisada", "customerId", UUID.randomUUID()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.description").value("Descrição revisada"))
                .andExpect(jsonPath("$.customerId").value(customer.toString())).andExpect(jsonPath("$.serialNumber").value("SERIAL"));
        inTenant(fixture.tenantA, () -> admin.update("UPDATE tenant_memberships SET role = 'ATTENDANT' WHERE user_id = ? AND tenant_id = ?", fixture.userId, fixture.tenantA));
        token = login();
        create(customer, "ATTENDANT");
    }
    @Test void validatesRequiredFieldsAndPaging() throws Exception {
        mvc.perform(post(url).header("Authorization", "Bearer " + token).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        UUID customer = customer(fixture.tenantA, "Cliente");
        for (var invalid : List.of(Map.of("customerId", customer, "category", "BAD", "model", "Modelo"),
                Map.of("customerId", customer, "category", "NOTEBOOK", "model", " "))) {
            mvc.perform(post(url).header("Authorization", "Bearer " + token).contentType("application/json").content(mapper.writeValueAsString(invalid))).andExpect(status().isBadRequest());
        }
        mvc.perform(get(url).param("page", "-1").header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest());
        mvc.perform(get(url).param("size", "101").header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest());
    }
}
