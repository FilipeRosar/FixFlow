package br.com.fixflow.shared.registration;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
class VerifiedSignupFlowIntegrationTest {
    @TestConfiguration static class Config { @Bean @Primary TestMail testMail(){return new TestMail();} }
    static class TestMail extends JavaMailSenderImpl {
        final List<SimpleMailMessage> sent=new CopyOnWriteArrayList<>(); boolean fail;
        @Override public void send(SimpleMailMessage message){if(fail)throw new MailSendException("Simulated failure");sent.add(new SimpleMailMessage(message));}
    }
    @Autowired MockMvc mvc;
    @Autowired Environment env;
    @Autowired ObjectMapper mapper;
    @Autowired TestMail mail;
    JdbcTemplate admin; TransactionTemplate tx;
    String email; String company; String origin; String jwt; UUID tenant; UUID user;
    final String password="Owner1234!";
    @BeforeEach void setup(){
        company=UUID.randomUUID().toString();email=company+"@example.invalid";origin=UUID.randomUUID().toString();mail.sent.clear();mail.fail=false;
        var ds=new DriverManagerDataSource(env.getRequiredProperty("spring.flyway.url"),env.getRequiredProperty("spring.flyway.user"),env.getRequiredProperty("spring.flyway.password"));
        admin=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
    }
    @AfterEach void cleanup(){
        if(tenant!=null)scope(()->{
            admin.update("DELETE FROM outbox_event WHERE tenant_id=?",tenant);admin.update("DELETE FROM audit_log WHERE tenant_id=?",tenant);
            admin.update("DELETE FROM work_orders WHERE tenant_id=?",tenant);admin.update("DELETE FROM customers WHERE tenant_id=?",tenant);
            String policy="signup_cleanup_"+tenant.toString().replace("-","");
            admin.execute("CREATE POLICY "+policy+" ON tenant_memberships FOR ALL TO fixflow_migrator USING(tenant_id='"+tenant+"'::uuid) WITH CHECK(tenant_id='"+tenant+"'::uuid)");
            admin.update("DELETE FROM tenant_memberships WHERE tenant_id=?",tenant);
            admin.execute("DROP POLICY "+policy+" ON tenant_memberships");admin.update("DELETE FROM tenants WHERE id=?",tenant);
        });
        admin.update("DELETE FROM pending_signups WHERE email=?",email);
        if(user!=null)admin.update("DELETE FROM users WHERE id=?",user);
    }
    void scope(Runnable op){tx.executeWithoutResult(s->{admin.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());op.run();});}
    String body(){return mapper.writeValueAsString(Map.of("companyName",company,"fullName","Owner Test","email",email,"password",password,"plan","STARTER"));}
    void request() throws Exception {
        mvc.perform(post("/api/contas").with(r->{r.setRemoteAddr(origin);return r;}).contentType("application/json").content(body()))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.userId").doesNotExist()).andExpect(jsonPath("$.token").doesNotExist());
    }
    String token(){String text=mail.sent.getLast().getText();var match=java.util.regex.Pattern.compile("#token=([A-Za-z0-9_-]{43})").matcher(text);assertTrue(match.find());return match.group(1);}
    JsonNode confirm(String token) throws Exception {
        var result=mvc.perform(post("/api/contas/confirmar").contentType("application/json").content(mapper.writeValueAsString(Map.of("token",token))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("OWNER")).andReturn();
        var account=mapper.readTree(result.getResponse().getContentAsString());tenant=UUID.fromString(account.path("tenantId").asText());user=UUID.fromString(account.path("userId").asText());return account;
    }
    void login() throws Exception {
        var result=mvc.perform(post("/api/v1/auth/login").header("X-Auth-Request","true").contentType("application/json")
            .content(mapper.writeValueAsString(Map.of("email",email,"password",password)))).andExpect(status().isOk()).andReturn();
        jwt=mapper.readTree(result.getResponse().getContentAsString()).path("accessToken").asText();
    }
    void saveProfile() throws Exception {
        String profile=mapper.writeValueAsString(Map.of("companyName",company,"document","52998224725","phone","11999999999","address",
            Map.of("street","Rua Teste","number","123","complement","","neighborhood","Centro","city","São Paulo","state","SP","postalCode","01001000")));
        mvc.perform(put("/api/v1/onboarding").header("Authorization","Bearer "+jwt).contentType("application/json").content(profile)).andExpect(status().isOk()).andExpect(jsonPath("$.profileCompleted").value(true));
    }
    @Test void emailConfirmationCreatesCompanyOwnerAndSelectedPlanTrialOnlyOnce() throws Exception {
        request();assertEquals(1,mail.sent.size());
        assertEquals(0,admin.queryForObject("SELECT count(*) FROM users WHERE email=?",Integer.class,email));
        assertEquals(0,admin.queryForObject("SELECT count(*) FROM tenants WHERE name=?",Integer.class,company));
        mvc.perform(post("/api/v1/auth/login").header("X-Auth-Request","true").contentType("application/json").content(mapper.writeValueAsString(Map.of("email",email,"password",password)))).andExpect(status().isUnauthorized());
        String token=token();confirm(token);
        final Object[] trial={null};scope(()->{trial[0]=admin.queryForObject("SELECT trial_started_at FROM tenants WHERE id=?",java.sql.Timestamp.class,tenant);
            assertEquals("STARTER",admin.queryForObject("SELECT plan FROM tenants WHERE id=?",String.class,tenant));
            assertNull(admin.queryForObject("SELECT onboarding_completed_at FROM tenants WHERE id=?",java.sql.Timestamp.class,tenant));});
        confirm(token);scope(()->assertEquals(trial[0],admin.queryForObject("SELECT trial_started_at FROM tenants WHERE id=?",java.sql.Timestamp.class,tenant)));
        assertNotNull(admin.queryForObject("SELECT email_verified_at FROM users WHERE id=?",java.sql.Timestamp.class,user));
        assertNull(admin.queryForObject("SELECT password_hash FROM pending_signups WHERE email=?",String.class,email));
        login();mvc.perform(get("/api/oficinas/"+tenant+"/selecao").header("Authorization","Bearer "+jwt)).andExpect(status().isOk()).andExpect(jsonPath("$.onboardingRequired").value(true));
    }
    @Test void signupProfileIsSavedOnlyAfterEmailConfirmation() throws Exception {
        var data = new LinkedHashMap<String,Object>();
        data.put("companyName",company);data.put("fullName","Owner Test");data.put("email",email);
        data.put("password",password);data.put("plan","STARTER");
        data.put("document","52998224725");data.put("phone","11999999999");
        data.put("address",Map.of("street","Rua Teste","number","123","complement","","neighborhood","Centro","city","São Paulo","state","SP","postalCode","01001000"));
        mvc.perform(post("/api/contas").with(r->{r.setRemoteAddr(origin);return r;}).contentType("application/json").content(mapper.writeValueAsString(data))).andExpect(status().isAccepted());
        assertEquals(0,admin.queryForObject("SELECT count(*) FROM tenants WHERE name=?",Integer.class,company));
        confirm(token());
        scope(()->{
            assertEquals("52998224725",admin.queryForObject("SELECT document FROM tenants WHERE id=?",String.class,tenant));
            assertEquals("01001000",admin.queryForObject("SELECT postal_code FROM tenants WHERE id=?",String.class,tenant));
            assertNotNull(admin.queryForObject("SELECT onboarding_profile_completed_at FROM tenants WHERE id=?",java.sql.Timestamp.class,tenant));
        });
        assertNull(admin.queryForObject("SELECT profile::text FROM pending_signups WHERE email=?",String.class,email));
    }
    @Test void ownerCanDeleteCompanyOnlyWithPasswordAndExplicitConfirmation() throws Exception {
        request();confirm(token());login();saveProfile();
        mvc.perform(delete("/api/v1/tenant").header("Authorization","Bearer "+jwt).contentType("application/json")
            .content(mapper.writeValueAsString(Map.of("password",password,"confirmation","wrong")))).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/tenant").header("Authorization","Bearer "+jwt).contentType("application/json")
            .content(mapper.writeValueAsString(Map.of("password","Wrong1234!","confirmation","EXCLUIR")))).andExpect(status().isBadRequest());
        // Deletion must remain possible after the trial and remove ledger rows too.
        scope(()->{
            UUID part=UUID.randomUUID();
            admin.update("INSERT INTO customers(tenant_id,name) VALUES(?,?)",tenant,"Cliente para exclusão");
            admin.update("INSERT INTO parts(id,tenant_id,sku,name,cost,sale_price) VALUES(?,?,?, ?,1,2)",part,tenant,"PART-DELETE","Peça");
            admin.update("INSERT INTO inventory_movements(id,tenant_id,part_id,type,quantity,unit_cost,created_by) VALUES(?,?,?,'ENTRY',1,1,?)",UUID.randomUUID(),tenant,part,user);
            admin.update("UPDATE tenants SET trial_ends_at=clock_timestamp()-interval '1 second' WHERE id=?",tenant);
        });
        UUID deleted=tenant;
        mvc.perform(delete("/api/v1/tenant").header("Authorization","Bearer "+jwt).contentType("application/json")
            .content(mapper.writeValueAsString(Map.of("password",password,"confirmation","EXCLUIR")))).andExpect(status().isNoContent());
        scope(()->assertEquals(0,admin.queryForObject("SELECT count(*) FROM tenants WHERE id=?",Integer.class,deleted)));
        assertEquals("SUSPENDED",admin.queryForObject("SELECT status FROM users WHERE id=?",String.class,user));
        assertEquals(0,admin.queryForObject("SELECT count(*) FROM pending_signups WHERE email=?",Integer.class,email));
        mvc.perform(get("/api/v1/tenant").header("Authorization","Bearer "+jwt)).andExpect(status().isUnauthorized());
        scope(()->{
            for(String table:List.of("customers","parts","inventory_movements","audit_log","outbox_event","tenant_memberships"))
                assertEquals(0,admin.queryForObject("SELECT count(*) FROM "+table+" WHERE tenant_id=?",Integer.class,deleted),table);
        });
        admin.update("DELETE FROM company_file_deletions WHERE tenant_id=?",deleted);
        tenant=null;
    }
    @Test void nonOwnerCannotDeleteCompany() throws Exception {
        request();confirm(token());login();
        scope(()->admin.update("UPDATE tenant_memberships SET role='TECHNICIAN' WHERE tenant_id=? AND user_id=?",tenant,user));
        login();
        mvc.perform(delete("/api/v1/tenant").header("Authorization","Bearer "+jwt).contentType("application/json")
            .content(mapper.writeValueAsString(Map.of("password",password,"confirmation","EXCLUIR")))).andExpect(status().isForbidden());
        scope(()->assertEquals(1,admin.queryForObject("SELECT count(*) FROM tenants WHERE id=?",Integer.class,tenant)));
    }
    @Test void deletingCompanyPreservesOtherCompanyAndSharedIdentity() throws Exception {
        request();confirm(token());login();
        UUID first=tenant;
        UUID other=UUID.randomUUID();
        tx.executeWithoutResult(status->{
            admin.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,other.toString());
            admin.update("INSERT INTO tenants(id,name) VALUES(?,?)",other,"Outra empresa");
            admin.update("INSERT INTO tenant_memberships(user_id,tenant_id,role) VALUES(?,?,'OWNER')",user,other);
            admin.update("INSERT INTO customers(tenant_id,name) VALUES(?,?)",other,"Cliente preservado");
        });
        try {
            mvc.perform(delete("/api/v1/tenant").header("Authorization","Bearer "+jwt).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("password",password,"confirmation","EXCLUIR")))).andExpect(status().isNoContent());
            assertEquals("ACTIVE",admin.queryForObject("SELECT status FROM users WHERE id=?",String.class,user));
            tenant=other;
            scope(()->assertEquals("Cliente preservado",admin.queryForObject("SELECT name FROM customers WHERE tenant_id=?",String.class,other)));
            login();
            mvc.perform(get("/api/v1/tenant").header("Authorization","Bearer "+jwt)).andExpect(status().isOk());
        } finally {
            tenant=other;
            admin.update("DELETE FROM company_file_deletions WHERE tenant_id=?",first);
        }
    }
    @Test void paymentReturnIsPublicNavigationAndIgnoresUntrustedRedirects() throws Exception {
        mvc.perform(get("/public/subscriptions/return").param("redirect","https://untrusted.invalid").param("status","approved"))
            .andExpect(status().isSeeOther())
            .andExpect(header().string("Location","http://localhost:4200/inicio/configuracoes/assinatura?assinatura=retorno"));
    }
    @Test void expiredAndRotatedTokensCannotCreateAccounts() throws Exception {
        request();String old=token();admin.update("UPDATE pending_signups SET expires_at=clock_timestamp()-interval '1 second',last_sent_at=clock_timestamp()-interval '2 minutes' WHERE email=?",email);
        mvc.perform(post("/api/contas/confirmar").contentType("application/json").content(mapper.writeValueAsString(Map.of("token",old)))).andExpect(status().isGone());
        request();assertEquals(2,mail.sent.size());String renewed=token();assertNotEquals(old,renewed);
        mvc.perform(post("/api/contas/confirmar").contentType("application/json").content(mapper.writeValueAsString(Map.of("token",old)))).andExpect(status().isGone());confirm(renewed);
    }
    @Test void smtpFailureRollsBackPendingSignupAndAllowsRetry() throws Exception {
        mail.fail=true;
        mvc.perform(post("/api/contas").with(r->{r.setRemoteAddr(origin);return r;}).contentType("application/json").content(body())).andExpect(status().isServiceUnavailable());
        assertEquals(0,admin.queryForObject("SELECT count(*) FROM pending_signups WHERE email=?",Integer.class,email));
        mail.fail=false;request();assertEquals(1,mail.sent.size());
    }
    @Test void resendsAreThrottledAndExistingAccountGetsUniformResponse() throws Exception {
        request();request();assertEquals(1,mail.sent.size());confirm(token());request();assertEquals(1,mail.sent.size());
    }
    @Test void simultaneousConfirmationDoesNotDuplicateTenantOrTrial() throws Exception {
        request();String token=token();
        try(var executor=Executors.newFixedThreadPool(2)){
            Callable<String> task=()->mvc.perform(post("/api/contas/confirmar").contentType("application/json").content(mapper.writeValueAsString(Map.of("token",token))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var a=executor.submit(task);var b=executor.submit(task);assertEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
        }
        confirm(token());assertEquals(1,admin.queryForObject("SELECT count(*) FROM tenants WHERE name=?",Integer.class,company));
    }
    @Test void guidedSetupRequiresRealFirstOrderAndExportStillWorksAfterTrial() throws Exception {
        request();confirm(token());login();saveProfile();
        mvc.perform(post("/api/v1/onboarding/complete").header("Authorization","Bearer "+jwt)).andExpect(status().isConflict());
        var c=mvc.perform(post("/api/v1/customers").header("Authorization","Bearer "+jwt).contentType("application/json").content("{\"name\":\"Ana\",\"contacts\":[]}"))
            .andExpect(status().isCreated()).andReturn();
        String customer=mapper.readTree(c.getResponse().getContentAsString()).path("id").asText();
        var e=mvc.perform(post("/api/v1/equipment").header("Authorization","Bearer "+jwt).contentType("application/json").content(mapper.writeValueAsString(Map.of("customerId",customer,"category","NOTEBOOK","model","Dell"))))
            .andExpect(status().isCreated()).andReturn();String equipment=mapper.readTree(e.getResponse().getContentAsString()).path("id").asText();
        mvc.perform(post("/api/v1/work-orders").header("Authorization","Bearer "+jwt).contentType("application/json").content(mapper.writeValueAsString(Map.of("customerId",customer,"equipmentId",equipment,"reportedIssue","Não liga","physicalCondition","Bom estado","receivedAccessories",List.of()))))
            .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/onboarding/complete").header("Authorization","Bearer "+jwt)).andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(true));
        mvc.perform(get("/api/oficinas/"+tenant+"/selecao").header("Authorization","Bearer "+jwt)).andExpect(status().isOk()).andExpect(jsonPath("$.onboardingRequired").value(false));
        scope(()->admin.update("UPDATE tenants SET trial_ends_at=clock_timestamp()-interval '1 second' WHERE id=?",tenant));
        mvc.perform(post("/api/v1/subscription/cancel").header("Authorization","Bearer "+jwt)).andExpect(status().isOk()).andExpect(jsonPath("$.access.status").value("CANCELED"));
        byte[] export=mvc.perform(get("/api/v1/tenant/export").header("Authorization","Bearer "+jwt)).andExpect(status().isOk())
            .andExpect(header().string("Content-Type","application/zip"))
            .andExpect(header().string("Content-Disposition","attachment; filename=fixflow-dados-csv.zip"))
            .andReturn().getResponse().getContentAsByteArray();
        var files=new java.util.HashMap<String,String>();
        try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(export),java.nio.charset.StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entry;
            while((entry=zip.getNextEntry())!=null) files.put(entry.getName(),new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        }
        assertEquals(13,files.size());
        assertTrue(files.get("clientes.csv").contains("Ana"));
        assertTrue(files.get("ordens_de_servico.csv").contains("Não liga"));
        assertTrue(files.get("pagamentos.csv").startsWith("\ufeff"));
        assertFalse(files.keySet().stream().anyMatch(name->name.contains("users")));
        assertFalse(files.values().stream().anyMatch(content->content.contains("password_hash")||content.contains("token_hash")));
        mvc.perform(post("/api/v1/customers").header("Authorization","Bearer "+jwt).contentType("application/json").content("{\"name\":\"Outro\",\"contacts\":[]}"))
            .andExpect(status().isPaymentRequired());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"PROFESSIONAL","BUSINESS"})
    void paidSignupHasNoFreeTrial(String plan) throws Exception {
        var payload = (tools.jackson.databind.node.ObjectNode) mapper.readTree(body());
        payload.put("plan", plan);
        mvc.perform(post("/api/contas").contentType("application/json")
            .content(mapper.writeValueAsString(payload))).andExpect(status().isAccepted());
        assertTrue(mail.sent.getFirst().getText().contains("pagamento"));
        confirm(token()); login();
        scope(() -> {
            assertEquals(plan, admin.queryForObject("SELECT plan FROM tenants WHERE id=?",String.class,tenant));
            assertTrue(admin.queryForObject("SELECT trial_ends_at<=clock_timestamp() FROM tenants WHERE id=?",Boolean.class,tenant));
        });
        mvc.perform(get("/api/v1/subscription/access").header("Authorization","Bearer "+jwt))
            .andExpect(status().isOk()).andExpect(jsonPath("$.writable").value(false));
        mvc.perform(post("/api/v1/subscription/plan").header("Authorization","Bearer "+jwt)
            .contentType("application/json").content("{\"plan\":\"BUSINESS\"}"))
            .andExpect(status().isConflict());
    }
    @Test void rejectsMalformedSignupAndCannotBypassEmailWithOldPayload() throws Exception {
        for(String field:List.of("fullName","companyName","email","password","plan")){
            var payload=(tools.jackson.databind.node.ObjectNode)mapper.readTree(body());payload.put(field,"");
            mvc.perform(post("/api/contas").contentType("application/json").content(mapper.writeValueAsString(payload))).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/contas").contentType("application/json").content("{\"firstName\":\"Owner\",\"lastName\":\"Test\",\"companyName\":\"Oficina\",\"email\":\"x@example.com\",\"password\":\"Owner1234!\"}"))
            .andExpect(status().isBadRequest());assertTrue(mail.sent.isEmpty());
    }
}
