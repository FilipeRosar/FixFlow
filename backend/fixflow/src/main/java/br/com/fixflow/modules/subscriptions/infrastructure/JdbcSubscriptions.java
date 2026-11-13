package br.com.fixflow.modules.subscriptions.infrastructure;

import br.com.fixflow.modules.subscriptions.application.Subscriptions;
import br.com.fixflow.modules.subscriptions.application.SubscriptionGateway;
import br.com.fixflow.modules.subscriptions.domain.SubscriptionAccess;
import br.com.fixflow.shared.application.audit.AuditService;
import br.com.fixflow.shared.application.outbox.OutboxService;
import br.com.fixflow.shared.application.tenant.AuthorizedTenantOperations;
import br.com.fixflow.shared.application.tenant.AuthorizedMembership;
import br.com.fixflow.shared.infrastructure.security.CurrentAuthenticatedIdentity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Function;

@Service
public class JdbcSubscriptions implements Subscriptions {
    private final JdbcTemplate jdbc;
    private final AuthorizedTenantOperations tenants;
    private final CurrentAuthenticatedIdentity identity;
    private final SubscriptionGateway gateway;
    private final AuditService audit;
    private final OutboxService outbox;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final List<Plan> catalog;
    public JdbcSubscriptions(JdbcTemplate jdbc,AuthorizedTenantOperations tenants,CurrentAuthenticatedIdentity identity,
            SubscriptionGateway gateway,AuditService audit,OutboxService outbox,ObjectMapper mapper,Clock clock,
            PlatformTransactionManager manager,@Value("${fixflow.subscriptions.price.starter:0}") BigDecimal starter,
            @Value("${fixflow.subscriptions.price.professional:0}") BigDecimal professional,
            @Value("${fixflow.subscriptions.price.business:0}") BigDecimal business) {
        this.jdbc=jdbc; this.tenants=tenants; this.identity=identity; this.gateway=gateway;
        this.audit=audit; this.outbox=outbox; this.mapper=mapper; this.clock=clock; transaction=new TransactionTemplate(manager);
        catalog=List.of(plan("STARTER","Starter",starter,2,100),plan("PROFESSIONAL","Professional",professional,6,500),plan("BUSINESS","Business",business,null,null));
    }
    private Plan plan(String code,String name,BigDecimal price,Integer users,Integer orders) {
        if(price.scale()>2 || price.signum()<0 || price.compareTo(new BigDecimal("999999999999.99"))>0) throw new IllegalArgumentException("Preço da assinatura inválido.");
        return new Plan(code,name,price,users,orders,price.signum()>0);
    }
    private UUID tenant() {
        UUID id=identity.require().tenantId();
        if(id==null) throw new AccessDeniedException("Selecione uma empresa.");
        return id;
    }
    private <T> T owner(Function<AuthorizedMembership,T> operation) {
        return tenants.execute(tenant(),m->{
            if(!m.role().name().equals("OWNER")) throw new AccessDeniedException("Assinatura exclusiva do proprietário.");
            return operation.apply(m);
        });
    }
    private Instant instant(java.sql.ResultSet rs,String column) throws java.sql.SQLException {
        var value=rs.getTimestamp(column); return value==null?null:value.toInstant();
    }
    private record Account(Instant trialStart,Instant trialEnd,Instant paidFrom,Instant paidUntil,Instant canceled,String plan) { }
    private Account account(UUID tenant,boolean lock) {
        return jdbc.queryForObject("SELECT * FROM public.tenants WHERE id=?"+(lock?" FOR UPDATE":""),
            (rs,n)->new Account(instant(rs,"trial_started_at"),instant(rs,"trial_ends_at"),instant(rs,"subscription_paid_from"),
                instant(rs,"subscription_paid_until"),instant(rs,"subscription_cancelled_at"),rs.getString("plan")),tenant);
    }
    private SubscriptionAccess access(Account a) {
        return SubscriptionAccess.evaluate(clock.instant(),a.trialEnd(),a.paidUntil(),a.canceled(),a.plan());
    }
    @Override public SubscriptionAccess access() { return tenants.execute(tenant(),m->access(account(m.tenantId(),false))); }
    private final RowMapper<Contract> contractRow=(rs,n)->new Contract(rs.getObject("id",UUID.class),rs.getString("plan"),
        rs.getBigDecimal("amount"),rs.getString("cycle"),rs.getString("state"),instant(rs,"cancel_requested_at"),rs.getString("checkout_url"));
    private Contract latest(UUID tenant) {
        var rows=jdbc.query("SELECT * FROM public.saas_contracts WHERE tenant_id=? ORDER BY created_at DESC,id DESC LIMIT 1",contractRow,tenant);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private View view(UUID tenant) {
        var a=account(tenant,false);
        var invoices=jdbc.query("SELECT * FROM public.saas_invoices WHERE tenant_id=? ORDER BY due_date DESC,id DESC LIMIT 100",
            (rs,n)->new Invoice(rs.getString("id"),rs.getBigDecimal("amount"),rs.getObject("due_date",LocalDate.class),rs.getString("status"),
                rs.getString("invoice_url"),instant(rs,"paid_at"),instant(rs,"applied_at")),tenant);
        long users=jdbc.queryForObject("SELECT count(*) FROM public.tenant_memberships WHERE tenant_id=? AND status='ACTIVE'",Long.class,tenant);
        long orders=jdbc.queryForObject("SELECT coalesce(sum(used),0) FROM public.tenant_order_usage WHERE tenant_id=? AND month=date_trunc('month',CURRENT_TIMESTAMP AT TIME ZONE 'America/Sao_Paulo')::date",Long.class,tenant);
        return new View(access(a),a.trialStart(),a.trialEnd(),a.plan(),a.paidFrom(),a.paidUntil(),a.canceled()!=null,
            latest(tenant),users,orders,catalog,invoices,gateway.available());
    }
    @Override public View load() { return owner(m->view(m.tenantId())); }
    private void event(AuthorizedMembership m,UUID contract,String type,Map<String,Object> details) {
        String json=mapper.writeValueAsString(details);
        audit.record(m.userId(),type,"SUBSCRIPTION",contract,json);
        outbox.record("SUBSCRIPTION",contract,type,json);
    }
    private ResponseStatusException conflict(String detail) { return new ResponseStatusException(HttpStatus.CONFLICT,detail); }
    private void checkPlanUsage(UUID tenant, Plan plan) {
        var current = view(tenant);
        if (plan.maxUsers()!=null && current.activeUsers()>plan.maxUsers()) throw conflict("O plano escolhido não comporta os usuários ativos. Reduza a equipe antes de alterar o plano.");
        if (plan.maxOrders()!=null && current.ordersThisMonth()>plan.maxOrders()) throw conflict("O uso de ordens de serviço deste mês excede o limite do plano escolhido.");
    }
    @Override public View changeTrialPlan(String code) {
        Plan selected = catalog.stream().filter(p -> p.code().equals(code)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,"Plano inválido."));
        return owner(m -> {
            account(m.tenantId(),true);
            checkPlanUsage(m.tenantId(),selected);
            if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT public.change_saas_trial_plan(?)",Boolean.class,code)))
                throw conflict("A troca sem cobrança está disponível somente durante o teste, sem contratação em andamento.");
            event(m,m.tenantId(),"TrialPlanChanged",Map.of("plan",code));
            return view(m.tenantId());
        });
    }
    @Override public View subscribe(String planCode,String key) {
        gateway.requireAvailable();
        Plan selected=catalog.stream().filter(p->p.code().equals(planCode)&&p.available()).findFirst()
            .orElseThrow(()->new ResponseStatusException(HttpStatus.BAD_REQUEST,"Plano indisponível para contratação."));
        UUID replacement=owner(m->{
            account(m.tenantId(),true);
            checkPlanUsage(m.tenantId(),selected);
            var previous=jdbc.query("SELECT * FROM public.saas_contracts WHERE tenant_id=? AND idempotency_key=?",contractRow,m.tenantId(),key);
            if(!previous.isEmpty()) {
                if(!previous.getFirst().plan().equals(planCode)) throw conflict("Chave já utilizada para outro plano.");
                return null;
            }
            Contract current=latest(m.tenantId());
            return current!=null&&!current.state().equals("CANCELED")&&!current.plan().equals(planCode)?current.id():null;
        });
        if(replacement!=null) cancel(replacement);
        UUID id=owner(m->{
            Account a=account(m.tenantId(),true);
            var previous=jdbc.query("SELECT * FROM public.saas_contracts WHERE tenant_id=? AND idempotency_key=?",contractRow,m.tenantId(),key);
            if(!previous.isEmpty()) {
                if(!previous.getFirst().plan().equals(planCode)) throw conflict("Chave já utilizada para outro plano.");
                return previous.getFirst().id();
            }
            Contract current=latest(m.tenantId());
            if(current!=null&&!current.state().equals("CANCELED")) {
                if(!current.plan().equals(planCode)) throw conflict("Há uma contratação em andamento. Atualize ou cancele a assinatura existente.");
                return current.id();
            }
            UUID contract=UUID.randomUUID();
            jdbc.update("INSERT INTO public.saas_contracts(id,tenant_id,plan,amount,idempotency_key,created_by) VALUES(?,?,?,?,?,?)",
                contract,m.tenantId(),selected.code(),selected.monthlyPrice(),key,m.userId());
            jdbc.update("UPDATE public.tenants SET subscription_cancelled_at=NULL WHERE id=?",m.tenantId());
            event(m,contract,"SubscriptionRequested",Map.of("plan",planCode,"amount",selected.monthlyPrice(),"currency","BRL","cycle","MONTHLY"));
            return contract;
        });
        provision(id);
        return refresh();
    }
    private record Attempt(UUID id,UUID tenant,String state,String customer,String remote,BigDecimal amount,String document,String name,String email) { }
    private Attempt attempt(UUID id) {
        return owner(m->{
            account(m.tenantId(),true);
            return jdbc.queryForObject("""
                SELECT c.*,t.document,t.name FROM public.saas_contracts c
                JOIN public.tenants t ON t.id=c.tenant_id
                WHERE c.id=? AND c.tenant_id=? FOR UPDATE OF c
                """,(rs,n)->new Attempt(id,m.tenantId(),rs.getString("state"),rs.getString("gateway_customer"),rs.getString("gateway_ref"),
                    rs.getBigDecimal("amount"),rs.getString("document"),rs.getString("name"),identity.require().email()),id,m.tenantId());
        });
    }
    private boolean claim(UUID id,String before,String after) {
        return owner(m->{ account(m.tenantId(),true);
            return jdbc.update("UPDATE public.saas_contracts SET state=? WHERE tenant_id=? AND id=? AND state=?",after,m.tenantId(),id,before)==1;
        });
    }
    private void provision(UUID id) {
        Attempt a=attempt(id);
        if(Set.of("ACTIVE","CANCELING","CANCELED").contains(a.state())) return;
        gateway.requireAvailable();
        if(a.customer()==null) {
            String document=a.document()==null?"":a.document().replaceAll("[^0-9]","");
            if(gateway.requiresDocument()&&!document.matches("[0-9]{11}|[0-9]{14}")) throw conflict("Cadastre CPF/CNPJ da empresa antes de contratar. Entre em contato com o suporte.");
            boolean fresh=claim(id,"READY","CUSTOMER_CREATING");
            Attempt initial=a;
            var customer=gateway.findCustomer(a.tenant());
            SubscriptionGateway.Customer payer=customer.orElseGet(()->{
                if(!fresh) throw conflict("Cadastro do pagador em conciliação. Atualize novamente; se persistir, contate o suporte.");
                return gateway.createCustomer(initial.tenant(),initial.name(),document,initial.email());
            });
            if(!payer.reference().equals("fixflow-subscription:"+a.tenant())||(gateway.requiresDocument()&&!payer.document().equals(document))) throw conflict("Pagador divergente do cadastro da empresa.");
            owner(m->{ account(m.tenantId(),true);
                jdbc.update("UPDATE public.saas_contracts SET gateway_customer=?,state='SUBSCRIPTION_READY' WHERE tenant_id=? AND id=? AND state='CUSTOMER_CREATING'",payer.id(),m.tenantId(),id);
                return null;
            });
        }
        a=attempt(id);
        if(a.remote()!=null || !Set.of("SUBSCRIPTION_READY","CREATING").contains(a.state())) return;
        boolean fresh=claim(id,"SUBSCRIPTION_READY","CREATING");
        Attempt request=a;
        var found=gateway.find(id);
        var remote=found.orElseGet(()->{
            if(!fresh) throw conflict("Contratação em conciliação. Atualize novamente; não crie outra cobrança. Se persistir, contate o suporte.");
            try {
                return gateway.create(id,request.customer(),request.email(),request.amount(),LocalDate.now(clock.withZone(ZoneId.of("America/Sao_Paulo"))));
            } catch (SubscriptionGateway.CreationRejected ex) {
                claim(id,"CREATING","SUBSCRIPTION_READY");
                throw ex;
            }
        });
        validate(request,remote);
        owner(m->{
            account(m.tenantId(),true);
            jdbc.update("UPDATE public.saas_contracts SET gateway_ref=?,checkout_url=?,state='ACTIVE' WHERE tenant_id=? AND id=? AND state='CREATING'",remote.id(),remote.checkoutUrl(),m.tenantId(),id);
            return null;
        });
    }
    private void validate(Attempt a,SubscriptionGateway.Recurrence r) {
        if(!r.reference().equals("fixflow-subscription:"+a.id())||!r.customer().equals(a.customer())||r.amount().compareTo(a.amount())!=0
            ||!r.cycle().equals("MONTHLY")||!r.billingType().equals(gateway.billingType())||r.deleted()) throw conflict("Assinatura do provedor divergente da contratação.");
    }
    private void reconcile(UUID id,String event,SubscriptionGateway.Recurrence remote,SubscriptionGateway.Payment payment) {
        if(!remote.id().equals(payment.subscription()) || !remote.customer().equals(payment.customer()) || remote.amount().compareTo(payment.amount())!=0)
            throw conflict("Cobrança divergente da assinatura.");
        transaction.executeWithoutResult(tx->{
            boolean accepted=Boolean.TRUE.equals(jdbc.queryForObject("SELECT public.reconcile_saas_invoice(?,?,?,?,?,?,?,?,?,?)",Boolean.class,
                id,event,remote.id(),payment.customer(),payment.id(),payment.amount(),java.sql.Date.valueOf(payment.dueDate()),payment.status(),
                payment.invoiceUrl(),payment.paidAt()==null?null:Timestamp.from(payment.paidAt())));
            if(!accepted) throw conflict("Confirmação divergente da assinatura registrada.");
        });
    }
    @Override public View refresh() {
        Contract c=owner(m->latest(m.tenantId()));
        if(c==null||c.state().equals("CANCELED")) return load();
        provision(c.id());
        Attempt a=attempt(c.id());
        if(a.state().equals("CANCELING")) return cancel();
        if(a.remote()!=null) {
            var remote=gateway.retrieve(a.remote());
            if (remote.deleted() && gateway.provider().equals("mercadopago")) {
                transaction.executeWithoutResult(tx -> jdbc.queryForObject("SELECT public.cancel_mercadopago_subscription(?,?)", Boolean.class, c.id(), remote.id()));
                return load();
            }
            validate(a,remote);
            owner(m -> {
                jdbc.update("UPDATE public.saas_contracts SET checkout_url=? WHERE tenant_id=? AND id=? AND state='ACTIVE'", remote.checkoutUrl(), m.tenantId(), c.id());
                return null;
            });
            // Oldest first: several paid cycles grant successive periods, once each.
            var payments=new ArrayList<>(gateway.payments(a.remote()));
            payments.sort(Comparator.comparing(SubscriptionGateway.Payment::dueDate).thenComparing(SubscriptionGateway.Payment::id));
            for(var p:payments) reconcile(c.id(),"sync:"+p.id()+":"+p.status(),remote,p);
        }
        return load();
    }
    @Override public View cancel() { return cancel(null); }
    private View cancel(UUID expected) {
        Attempt a=owner(m->{
            account(m.tenantId(),true);
            Contract c=latest(m.tenantId());
            if(expected!=null && (c==null || !expected.equals(c.id()))) throw conflict("A assinatura foi alterada em outra operação. Recarregue antes de tentar novamente.");
            if(c==null) {
                if(jdbc.update("UPDATE public.tenants SET subscription_cancelled_at=clock_timestamp() WHERE id=? AND subscription_cancelled_at IS NULL",m.tenantId())==1)
                    event(m,m.tenantId(),"SubscriptionCanceled",Map.of("previousState","TRIALING","state","CANCELED_AT_PERIOD_END"));
                return null;
            }
            if(c.state().equals("CANCELED")) return null;
            String remote=jdbc.queryForObject("SELECT gateway_ref FROM public.saas_contracts WHERE tenant_id=? AND id=?",String.class,m.tenantId(),c.id());
            if(remote==null) {
                if(c.state().equals("CREATING")) throw conflict("Atualize a contratação em andamento antes de cancelar.");
                jdbc.update("UPDATE public.saas_contracts SET state='CANCELED',cancel_requested_at=clock_timestamp() WHERE tenant_id=? AND id=?",m.tenantId(),c.id());
                jdbc.update("UPDATE public.tenants SET subscription_cancelled_at=clock_timestamp() WHERE id=?",m.tenantId());
                event(m,c.id(),"SubscriptionCanceled",Map.of("previousState",c.state(),"state","CANCELED"));
                return null;
            }
            if(!c.state().equals("CANCELING")) {
                jdbc.update("UPDATE public.saas_contracts SET state='CANCELING',cancel_requested_at=clock_timestamp() WHERE tenant_id=? AND id=?",m.tenantId(),c.id());
                jdbc.update("UPDATE public.tenants SET subscription_cancelled_at=clock_timestamp() WHERE id=?",m.tenantId());
                event(m,c.id(),"SubscriptionCancellationRequested",Map.of("previousState",c.state(),"state","CANCELING","gateway",remote));
            }
            return new Attempt(c.id(),m.tenantId(),"CANCELING",null,remote,c.amount(),null,null,null);
        });
        if(a!=null) {
            gateway.cancel(a.remote());
            owner(m->{ account(m.tenantId(),true);
                if(jdbc.update("UPDATE public.saas_contracts SET state='CANCELED' WHERE tenant_id=? AND id=? AND state='CANCELING'",m.tenantId(),a.id())==1)
                    event(m,a.id(),"SubscriptionCanceled",Map.of("previousState","CANCELING","state","CANCELED","accessUntil",access(account(m.tenantId(),false)).accessUntil().toString()));
                return null;
            });
        }
        return load();
    }
    @Override public void mercadoPagoNotification(String type, String resourceId) {
        if (!gateway.provider().equals("mercadopago"))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Provedor de assinatura indisponível.");
        SubscriptionGateway.Recurrence remote;
        if ("subscription_authorized_payment".equals(type)) {
            var payment = gateway.payment("pay_mp" + resourceId);
            remote = gateway.retrieve(payment.subscription());
            UUID contract = mercadoPagoContract(remote);
            if (remote.deleted()) cancelMercadoPagoContract(contract, remote.id());
            reconcile(contract, "mp:" + payment.id() + ":" + payment.status(), remote, payment);
        } else if ("subscription_preapproval".equals(type)) {
            remote = gateway.retrieve("sub_mp" + resourceId);
            UUID contract = mercadoPagoContract(remote);
            if (remote.deleted()) {
                cancelMercadoPagoContract(contract, remote.id());
            } else {
                for (var payment : gateway.payments(remote.id()))
                    reconcile(contract, "mp:" + payment.id() + ":" + payment.status(), remote, payment);
            }
        }
    }
    private void cancelMercadoPagoContract(UUID contract, String remote) {
        transaction.executeWithoutResult(tx -> jdbc.queryForObject(
            "SELECT public.cancel_mercadopago_subscription(?,?)", Boolean.class, contract, remote));
    }
    private UUID mercadoPagoContract(SubscriptionGateway.Recurrence remote) {
        try { return UUID.fromString(remote.reference().substring("fixflow-subscription:".length())); }
        catch (RuntimeException ex) { throw conflict("Referência da assinatura inválida."); }
    }
    @Override public void webhook(String token,String eventId,String event,String paymentId) {
        gateway.authenticate(token);
        if(eventId==null||!eventId.matches("evt_[a-zA-Z0-9_-]{1,195}")||event==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Evento inválido.");
        if(!event.startsWith("PAYMENT_")) return;
        var payment=gateway.payment(paymentId);
        if(payment.subscription()==null||!payment.subscription().matches("sub_[a-zA-Z0-9]+")) return;
        var remote=gateway.retrieve(payment.subscription());
        if(!remote.reference().startsWith("fixflow-subscription:")) return;
        UUID id;
        try { id=UUID.fromString(remote.reference().substring("fixflow-subscription:".length())); }
        catch(IllegalArgumentException ex) { throw conflict("Referência da assinatura inválida."); }
        reconcile(id,eventId,remote,payment);
    }
}
