package br.com.fixflow.modules.subscriptions;

import br.com.fixflow.modules.subscriptions.application.SubscriptionGateway;
import br.com.fixflow.support.MembershipFixtures;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"fixflow.subscriptions.price.starter=69.00","fixflow.subscriptions.price.professional=159.00",
    "fixflow.subscriptions.enabled=true", "fixflow.subscriptions.mercadopago-access-token=test-token",
    "fixflow.subscriptions.mercadopago-webhook-secret=test-secret",
    "fixflow.subscriptions.mercadopago-back-url=https://fixflow.example/retorno",
    "fixflow.subscriptions.mercadopago-notification-url=https://fixflow.example/webhooks/mercadopago/subscriptions"})
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
class SubscriptionsIntegrationTest {
    @TestConfiguration static class Config { @Bean @Primary FakeGateway fakeGateway() { return new FakeGateway(); } }
    static class FakeGateway implements SubscriptionGateway {
        Customer customer; Recurrence recurrence; final Map<String,Payment> payments=new HashMap<>(); int creates; int cancellations; boolean timeout; boolean cancelFails; boolean mercadoPago;
        void reset() { customer=null; recurrence=null; payments.clear(); creates=0; cancellations=0; timeout=false; cancelFails=false; mercadoPago=false; }
        public String provider(){return mercadoPago?"mercadopago":"asaas";}
        public String billingType(){return mercadoPago?"MERCADO_PAGO":"PIX";}
        public boolean available(){return true;}
        public void requireAvailable(){}
        public void authenticate(String token){if(!"test-secret".equals(token)) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED);}
        public Optional<Customer> findCustomer(UUID t){return Optional.ofNullable(customer);}
        public Customer createCustomer(UUID t,String n,String d,String e){customer=new Customer("cus_test","fixflow-subscription:"+t,d);return customer;}
        public Optional<Recurrence> find(UUID c){return Optional.ofNullable(recurrence).filter(r->r.reference().equals("fixflow-subscription:"+c));}
        public Recurrence create(UUID c,String payer,BigDecimal value,LocalDate date){
            creates++; String sub=creates==1?"sub_test":"sub_test"+creates; String invoice=creates==1?"pay_test":"pay_test"+creates; if(mercadoPago){sub="sub_mpabc123";invoice="pay_mp123";} recurrence=new Recurrence(sub,"fixflow-subscription:"+c,payer,value,"MONTHLY",billingType(),false,mercadoPago?"https://www.mercadopago.com.br/subscriptions/checkout?preapproval_id=abc123":null);
            payments.put(invoice,new Payment(invoice,sub,payer,value,date,"PENDING","https://www.asaas.com/i/test",null));
            if(timeout) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY,"Timeout");
            return recurrence;
        }
        public Recurrence retrieve(String id){return recurrence;}
        public void cancel(String id){if(cancelFails) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY); cancellations++;}
        public List<Payment> payments(String id){return payments.values().stream().filter(p->p.subscription().equals(id)).toList();}
        public Payment payment(String id){return payments.get(id);}
        void receive(String id){var p=payments.get(id);payments.put(id,new Payment(p.id(),p.subscription(),p.customer(),p.amount(),p.dueDate(),"RECEIVED",p.invoiceUrl(),Instant.now()));}
    }
    @Autowired MockMvc mvc;
    @Autowired Environment environment;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;
    @Autowired FakeGateway gateway;
    @Autowired JdbcTemplate runtime;
    @Autowired org.springframework.transaction.PlatformTransactionManager applicationManager;
    @Autowired br.com.fixflow.modules.subscriptions.infrastructure.SubscriptionCancellationListener cancellationListener;
    MembershipFixtures fixture;
    JdbcTemplate admin;
    TransactionTemplate transaction;
    String token;
    @BeforeEach void setup() throws Exception {
        gateway.reset(); fixture=new MembershipFixtures(environment,encoder);
        var ds=new DriverManagerDataSource(environment.getRequiredProperty("spring.flyway.url"),environment.getRequiredProperty("spring.flyway.user"),environment.getRequiredProperty("spring.flyway.password"));
        admin=new JdbcTemplate(ds);transaction=new TransactionTemplate(new DataSourceTransactionManager(ds));
        tenant(fixture.tenantA,()->admin.update("UPDATE tenants SET document='12345678901' WHERE id=?",fixture.tenantA));
        token=login();
    }
    String login() throws Exception {
        var result=mvc.perform(post("/api/v1/auth/login").header("X-Auth-Request","true").contentType("application/json")
            .content(mapper.writeValueAsString(Map.of("email",fixture.email,"password",fixture.password)))).andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).path("accessToken").asText();
    }
    void tenant(UUID id,Runnable action){transaction.executeWithoutResult(tx->{admin.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,id.toString());action.run();});}
    @AfterEach void cleanup(){
        for(var id:List.of(fixture.tenantA,fixture.tenantB)) tenant(id,()->{
            admin.update("DELETE FROM outbox_event WHERE tenant_id=?",id);admin.update("DELETE FROM audit_log WHERE tenant_id=?",id);
        });
        fixture.close();
    }
    void subscribe() throws Exception {
        mvc.perform(post("/api/v1/subscription").header("Authorization","Bearer "+token).header("Idempotency-Key","test-subscription-key").contentType("application/json").content("{\"plan\":\"STARTER\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.contract.amount").value(69)).andExpect(jsonPath("$.invoices[0].status").value("PENDING"));
    }
    void webhook(String event) throws Exception {
        mvc.perform(post("/webhooks/asaas/subscriptions").header("asaas-access-token","test-secret").contentType("application/json")
            .content("{\"id\":\""+event+"\",\"event\":\"PAYMENT_RECEIVED\",\"payment\":{\"id\":\"pay_test\"}}"))
            .andExpect(status().isOk());
    }
    String mercadoPagoSignature(String resource) throws Exception {
        var mac=javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec("test-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA256"));
        String manifest="id:"+resource+";request-id:request-123;ts:1794258000;";
        return "ts=1794258000,v1="+HexFormat.of().formatHex(mac.doFinal(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    void mercadoPagoWebhook(String resource,String type) throws Exception {
        mvc.perform(post("/webhooks/mercadopago/subscriptions").queryParam("data.id",resource)
            .header("x-signature",mercadoPagoSignature(resource)).header("x-request-id","request-123").contentType("application/json")
            .content(mapper.writeValueAsString(Map.of("type",type,"data",Map.of("id",resource)))))
            .andExpect(status().isNoContent());
    }
    @Test void mercadoPagoCheckoutAndSignedNotificationsActivateOnlyOnce() throws Exception {
        gateway.mercadoPago=true;
        subscribe();
        mvc.perform(get("/api/v1/subscription").header("Authorization","Bearer "+token))
            .andExpect(jsonPath("$.contract.checkoutUrl").value("https://www.mercadopago.com.br/subscriptions/checkout?preapproval_id=abc123"))
            .andExpect(jsonPath("$.access.status").value("TRIALING"));
        gateway.receive("pay_mp123");
        mercadoPagoWebhook("123","subscription_authorized_payment");
        final Instant[] until={null};
        tenant(fixture.tenantA,()->until[0]=admin.queryForObject("SELECT subscription_paid_until FROM tenants WHERE id=?",java.sql.Timestamp.class,fixture.tenantA).toInstant());
        mercadoPagoWebhook("123","subscription_authorized_payment");
        tenant(fixture.tenantA,()->{
            assertEquals(until[0],admin.queryForObject("SELECT subscription_paid_until FROM tenants WHERE id=?",java.sql.Timestamp.class,fixture.tenantA).toInstant());
            assertEquals(1,admin.queryForObject("SELECT count(*) FROM saas_invoices WHERE tenant_id=? AND applied_at IS NOT NULL",Integer.class,fixture.tenantA));
        });
    }
    @Test void mercadoPagoExternalCancellationPreservesPaidAccessAndIsIdempotent() throws Exception {
        gateway.mercadoPago=true;
        subscribe();gateway.receive("pay_mp123");mercadoPagoWebhook("123","subscription_authorized_payment");
        var r=gateway.recurrence;
        gateway.recurrence=new SubscriptionGateway.Recurrence(r.id(),r.reference(),r.customer(),r.amount(),r.cycle(),r.billingType(),true);
        mercadoPagoWebhook("abc123","subscription_preapproval");
        mercadoPagoWebhook("abc123","subscription_preapproval");
        mvc.perform(get("/api/v1/subscription").header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.contract.state").value("CANCELED"))
            .andExpect(jsonPath("$.cancelAtPeriodEnd").value(true)).andExpect(jsonPath("$.access.writable").value(true));
    }
    @Test void mercadoPagoWebhookRequiresValidSignatureAndMatchingResource() throws Exception {
        mvc.perform(post("/webhooks/mercadopago/subscriptions").queryParam("data.id","123").contentType("application/json")
            .content("{\"type\":\"subscription_authorized_payment\",\"data\":{\"id\":\"123\"}}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/webhooks/mercadopago/subscriptions").queryParam("data.id","123")
            .header("x-signature",mercadoPagoSignature("123")).header("x-request-id","request-123").contentType("application/json")
            .content("{\"type\":\"subscription_authorized_payment\",\"data\":{\"id\":\"456\"}}"))
            .andExpect(status().isBadRequest());
    }
    @Test void changesTrialPlanWithoutStartingBillingOrExtendingTrial() throws Exception {
        final Instant[] end={null};
        tenant(fixture.tenantA,()->end[0]=admin.queryForObject("SELECT trial_ends_at FROM tenants WHERE id=?",java.sql.Timestamp.class,fixture.tenantA).toInstant());
        mvc.perform(post("/api/v1/subscription/plan").header("Authorization","Bearer "+token).contentType("application/json")
            .content("{\"plan\":\"PROFESSIONAL\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.currentPlan").value("PROFESSIONAL"))
            .andExpect(jsonPath("$.access.status").value("TRIALING"));
        assertEquals(0,gateway.creates);
        tenant(fixture.tenantA,()->assertEquals(end[0],admin.queryForObject("SELECT trial_ends_at FROM tenants WHERE id=?",java.sql.Timestamp.class,fixture.tenantA).toInstant()));
    }
    @Test void rejectsFreePlanChangeAfterTrialExpires() throws Exception {
        tenant(fixture.tenantA,()->admin.update("UPDATE tenants SET trial_ends_at=clock_timestamp()-interval '1 second' WHERE id=?",fixture.tenantA));
        mvc.perform(post("/api/v1/subscription/plan").header("Authorization","Bearer "+token).contentType("application/json")
            .content("{\"plan\":\"PROFESSIONAL\"}")).andExpect(status().isConflict());
    }
    @Test void paidPlanReplacementCancelsOldRecurrenceAndChangesOnlyAfterPayment() throws Exception {
        subscribe();gateway.receive("pay_test");webhook("evt_firstplan");
        mvc.perform(post("/api/v1/subscription").header("Authorization","Bearer "+token).header("Idempotency-Key","replacement-plan-key").contentType("application/json")
            .content("{\"plan\":\"PROFESSIONAL\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.currentPlan").value("STARTER"))
            .andExpect(jsonPath("$.contract.plan").value("PROFESSIONAL"));
        assertEquals(1,gateway.cancellations);assertEquals(2,gateway.creates);
        gateway.receive("pay_test2");
        mvc.perform(post("/api/v1/subscription/refresh").header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.currentPlan").value("PROFESSIONAL"));
        mvc.perform(post("/api/v1/subscription").header("Authorization","Bearer "+token).header("Idempotency-Key","replacement-plan-key").contentType("application/json")
            .content("{\"plan\":\"PROFESSIONAL\"}")).andExpect(status().isOk());
        assertEquals(1,gateway.cancellations);assertEquals(2,gateway.creates);
    }
    @Test void failedCancellationDoesNotCreateSecondRecurrence() throws Exception {
        subscribe();gateway.cancelFails=true;
        mvc.perform(post("/api/v1/subscription").header("Authorization","Bearer "+token).header("Idempotency-Key","replacement-plan-key").contentType("application/json")
            .content("{\"plan\":\"PROFESSIONAL\"}")).andExpect(status().isBadGateway());
        assertEquals(1,gateway.creates);
    }
    @Test void trialIsVisibleAndNotRestartedOnRead() throws Exception {
        mvc.perform(get("/api/v1/subscription").header("Authorization","Bearer "+token)).andExpect(status().isOk())
            .andExpect(jsonPath("$.access.status").value("TRIALING")).andExpect(jsonPath("$.access.trialDaysRemaining").value(14))
            .andExpect(jsonPath("$.plans[0].monthlyPrice").value(69)).andExpect(jsonPath("$.activeUsers").value(1));
        tenant(fixture.tenantA,()->admin.update("UPDATE tenants SET trial_ends_at=clock_timestamp()-interval '1 second' WHERE id=?",fixture.tenantA));
        mvc.perform(get("/api/v1/subscription/access").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXPIRED"));
        mvc.perform(post("/api/v1/customers").header("Authorization","Bearer "+token).contentType("application/json").content("{}"))
            .andExpect(status().isPaymentRequired()).andExpect(jsonPath("$.code").value("SUBSCRIPTION_REQUIRED"));
        mvc.perform(get("/api/v1/customers").header("Authorization","Bearer "+token)).andExpect(status().isOk());
    }
    @Test void confirmedPaymentActivatesOnceWithoutWorkOrderPayments() throws Exception {
        subscribe(); subscribe(); assertEquals(1,gateway.creates);
        gateway.receive("pay_test");webhook("evt_confirmed");
        final Instant[] until={null};tenant(fixture.tenantA,()->until[0]=admin.queryForObject("SELECT subscription_paid_until FROM tenants WHERE id=?",java.sql.Timestamp.class,fixture.tenantA).toInstant());
        webhook("evt_confirmed");webhook("evt_duplicate");
        mvc.perform(post("/api/v1/subscription/refresh").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.access.status").value("ACTIVE"));
        tenant(fixture.tenantA,()->{
            assertEquals(until[0],admin.queryForObject("SELECT subscription_paid_until FROM tenants WHERE id=?",java.sql.Timestamp.class,fixture.tenantA).toInstant());
            assertEquals(0,admin.queryForObject("SELECT count(*) FROM payments WHERE tenant_id=?",Integer.class,fixture.tenantA));
            assertEquals(1,admin.queryForObject("SELECT count(*) FROM saas_invoices WHERE tenant_id=? AND applied_at IS NOT NULL",Integer.class,fixture.tenantA));
        });
    }
    @Test void timeoutIsRecoveredWithoutPostingASecondSubscription() throws Exception {
        gateway.timeout=true;
        mvc.perform(post("/api/v1/subscription").header("Authorization","Bearer "+token).header("Idempotency-Key","test-subscription-key").contentType("application/json").content("{\"plan\":\"STARTER\"}"))
            .andExpect(status().isBadGateway());
        mvc.perform(post("/api/v1/subscription/refresh").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        assertEquals(1,gateway.creates);
    }
    @Test void cancellationPreservesPaidPeriodAndLatePaymentDoesNotReactivate() throws Exception {
        subscribe(); gateway.receive("pay_test");webhook("evt_first");
        mvc.perform(post("/api/v1/subscription/cancel").header("Authorization","Bearer "+token)).andExpect(status().isOk())
            .andExpect(jsonPath("$.cancelAtPeriodEnd").value(true)).andExpect(jsonPath("$.access.writable").value(true));
        mvc.perform(post("/api/v1/subscription/cancel").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        assertEquals(1,gateway.cancellations);
        tenant(fixture.tenantA,()->admin.update("UPDATE tenants SET subscription_paid_until=clock_timestamp()-interval '1 second' WHERE id=?",fixture.tenantA));
        var old=gateway.payments.get("pay_test");gateway.payments.put("pay_test",new SubscriptionGateway.Payment(old.id(),old.subscription(),old.customer(),old.amount(),old.dueDate(),"PENDING",old.invoiceUrl(),null));
        webhook("evt_old_pending");
        mvc.perform(get("/api/v1/subscription").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.access.status").value("CANCELED"))
            .andExpect(jsonPath("$.invoices[0].status").value("RECEIVED"));
        gateway.receive("pay_test");webhook("evt_late");
        mvc.perform(get("/api/v1/subscription/access").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.writable").value(false));
    }
    @Test void rejectsBadTokenAndDivergentValue() throws Exception {
        subscribe();
        mvc.perform(post("/webhooks/asaas/subscriptions").contentType("application/json").content("{\"id\":\"evt_bad\",\"event\":\"PAYMENT_RECEIVED\",\"payment\":{\"id\":\"pay_test\"}}"))
            .andExpect(status().isUnauthorized());
        var p=gateway.payments.get("pay_test");gateway.payments.put("pay_test",new SubscriptionGateway.Payment(p.id(),p.subscription(),p.customer(),BigDecimal.ONE,p.dueDate(),"RECEIVED",p.invoiceUrl(),Instant.now()));
        mvc.perform(post("/webhooks/asaas/subscriptions").header("asaas-access-token","test-secret").contentType("application/json")
            .content("{\"id\":\"evt_badvalue\",\"event\":\"PAYMENT_RECEIVED\",\"payment\":{\"id\":\"pay_test\"}}"))
            .andExpect(status().isConflict());
    }
    @Test void staffCannotManageSubscriptionButCanReadAccessState() throws Exception {
        tenant(fixture.tenantA,()->admin.update("UPDATE tenant_memberships SET role='ATTENDANT' WHERE tenant_id=? AND user_id=?",fixture.tenantA,fixture.userId));
        token=login();
        mvc.perform(get("/api/v1/subscription").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/subscription/access").header("Authorization","Bearer "+token)).andExpect(status().isOk());
    }
    @Test void explicitRecontractAfterCancellationReopensPaymentInstructions() throws Exception {
        subscribe();
        mvc.perform(post("/api/v1/subscription/cancel").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/subscription").header("Authorization","Bearer "+token).header("Idempotency-Key","new-subscription-key").contentType("application/json").content("{\"plan\":\"STARTER\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.cancelAtPeriodEnd").value(false));
        assertEquals(2,gateway.creates);
    }
    @Test void earlyRenewalAddsOneMonthToExistingPaidPeriod() throws Exception {
        subscribe(); gateway.receive("pay_test"); webhook("evt_initial");
        final Instant[] before={null}; tenant(fixture.tenantA,()->before[0]=admin.queryForObject("SELECT subscription_paid_until FROM tenants WHERE id=?",java.sql.Timestamp.class,fixture.tenantA).toInstant());
        var p=gateway.payments.get("pay_test");
        gateway.payments.put("pay_renew",new SubscriptionGateway.Payment("pay_renew",p.subscription(),p.customer(),p.amount(),p.dueDate().plusMonths(1),"RECEIVED",p.invoiceUrl(),Instant.now()));
        mvc.perform(post("/api/v1/subscription/refresh").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        tenant(fixture.tenantA,()->assertEquals(before[0].atZone(ZoneId.of("America/Sao_Paulo")).plusMonths(1).toInstant(),admin.queryForObject("SELECT subscription_paid_until FROM tenants WHERE id=?",java.sql.Timestamp.class,fixture.tenantA).toInstant()));
    }
    @Test void cancellationFailureIsDurableAndOutboxRetryCompletesIt() throws Exception {
        subscribe(); gateway.cancelFails=true;
        mvc.perform(post("/api/v1/subscription/cancel").header("Authorization","Bearer "+token)).andExpect(status().isBadGateway());
        mvc.perform(get("/api/v1/subscription").header("Authorization","Bearer "+token)).andExpect(status().isOk())
            .andExpect(jsonPath("$.cancelAtPeriodEnd").value(true)).andExpect(jsonPath("$.contract.state").value("CANCELING"));
        gateway.cancelFails=false;
        var tx=new TransactionTemplate(applicationManager);
        tx.executeWithoutResult(status->{
            runtime.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,fixture.tenantA.toString());
            var event=runtime.queryForObject("SELECT * FROM outbox_event WHERE tenant_id=? AND event_type='SubscriptionCancellationRequested'",
                (rs,n)->new br.com.fixflow.shared.application.outbox.OutboxEvent(rs.getObject("id",UUID.class),fixture.tenantA,
                    rs.getString("aggregate_type"),rs.getObject("aggregate_id",UUID.class),rs.getString("event_type"),rs.getString("payload"),rs.getTimestamp("created_at").toInstant()),fixture.tenantA);
            cancellationListener.on(event);
        });
        mvc.perform(get("/api/v1/subscription").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.contract.state").value("CANCELED"));
        assertEquals(1,gateway.cancellations);
    }
    @Test void expirationIsAuditedAndNoticeIsQueuedOnce() {
        tenant(fixture.tenantA,()->admin.update("UPDATE tenants SET trial_ends_at=clock_timestamp()-interval '1 second' WHERE id=?",fixture.tenantA));
        runtime.queryForObject("SELECT public.observe_saas_subscriptions()",Object.class);
        runtime.queryForObject("SELECT public.observe_saas_subscriptions()",Object.class);
        tenant(fixture.tenantA,()->{
            assertEquals(1,admin.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id=? AND action='SUBSCRIPTION_ACCESS_CHANGED'",Integer.class,fixture.tenantA));
            assertEquals(1,admin.queryForObject("SELECT count(*) FROM outbox_event WHERE tenant_id=? AND event_type='SubscriptionAccessChanged'",Integer.class,fixture.tenantA));
        });
    }
    @Test void runtimeCannotReadOtherTenantContracts() throws Exception {
        subscribe();
        var tx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(runtime.getDataSource())));
        tx.executeWithoutResult(status->{
            runtime.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,fixture.tenantB.toString());
            assertEquals(0,runtime.queryForObject("SELECT count(*) FROM saas_contracts WHERE tenant_id=?",Integer.class,fixture.tenantA));
        });
    }
}
