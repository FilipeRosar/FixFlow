package br.com.fixflow.modules.subscriptions.infrastructure;

import br.com.fixflow.modules.subscriptions.application.SubscriptionGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;

@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="fixflow.subscriptions.provider", havingValue="asaas")
public class AsaasSubscriptionGateway implements SubscriptionGateway {
    private final RestClient client;
    private final boolean enabled;
    private final String key;
    private final String secret;
    public AsaasSubscriptionGateway(@Value("${fixflow.subscriptions.enabled:false}") boolean enabled,
            @Value("${fixflow.subscriptions.asaas-base-url:https://api-sandbox.asaas.com/v3}") String url,
            @Value("${fixflow.subscriptions.asaas-api-key:}") String key,
            @Value("${fixflow.subscriptions.webhook-token:}") String secret) {
        this.enabled=enabled; this.key=key; this.secret=secret;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        client=RestClient.builder().baseUrl(url).requestFactory(factory).defaultHeader("access_token",key)
            .defaultHeader("User-Agent","FixFlow/1.0").build();
    }
    @Override public boolean available() { return enabled && !key.isBlank() && secret.matches("\\S{32,255}") && !key.equals(secret); }
    @Override public void requireAvailable() {
        if (!available()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Cobrança da assinatura ainda não configurada. Entre em contato com o suporte.");
    }
    @Override public void authenticate(String token) {
        requireAvailable();
        if (token==null || !MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Webhook não autorizado.");
    }
    private ResponseStatusException failure() { return new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Não foi possível confirmar a operação no Asaas. Atualize a assinatura para conciliar a mesma solicitação."); }
    private JsonNode get(String path) {
        requireAvailable();
        try { var value=client.get().uri(path).retrieve().body(JsonNode.class); if(value==null) throw failure(); return value; }
        catch(RestClientException ex) { throw failure(); }
    }
    private JsonNode post(String path, Map<String,Object> body) {
        requireAvailable();
        try { var value=client.post().uri(path).body(body).retrieve().body(JsonNode.class); if(value==null) throw failure(); return value; }
        catch(RestClientException ex) { throw failure(); }
    }
    private Optional<JsonNode> single(JsonNode value) {
        if (!value.path("data").isArray() || value.path("data").size()>1 || value.path("hasMore").asBoolean(false)) throw failure();
        return value.path("data").isEmpty()?Optional.empty():Optional.of(value.path("data").get(0));
    }
    private String reference(UUID id) { return "fixflow-subscription:"+id; }
    private String id(JsonNode value, String prefix) {
        String id=value.path("id").asText(); if(!id.matches(prefix+"_[a-zA-Z0-9]+")) throw failure(); return id;
    }
    private Customer customer(JsonNode value) {
        if(value.path("deleted").asBoolean(false)) throw failure();
        return new Customer(id(value,"cus"),value.path("externalReference").asText(),value.path("cpfCnpj").asText().replaceAll("[^0-9]",""));
    }
    @Override public Optional<Customer> findCustomer(UUID tenant) { return single(get("/customers?externalReference="+reference(tenant)+"&limit=2")).map(this::customer); }
    @Override public Customer createCustomer(UUID tenant,String name,String document,String email) {
        return customer(post("/customers",Map.of("name",name,"cpfCnpj",document,"email",email,"externalReference",reference(tenant),"notificationDisabled",true)));
    }
    private Recurrence recurrence(JsonNode value) {
        if(!value.path("value").isNumber()) throw failure();
        return new Recurrence(id(value,"sub"),value.path("externalReference").asText(),value.path("customer").asText(),
            value.path("value").decimalValue(),value.path("cycle").asText(),value.path("billingType").asText(),value.path("deleted").asBoolean(false));
    }
    @Override public Optional<Recurrence> find(UUID contract) { return single(get("/subscriptions?externalReference="+reference(contract)+"&includeDeleted=true&limit=2")).map(this::recurrence); }
    @Override public Recurrence create(UUID contract,String customer,BigDecimal amount,LocalDate due) {
        return recurrence(post("/subscriptions",Map.of("customer",customer,"value",amount,"cycle","MONTHLY","billingType","PIX",
            "nextDueDate",due.toString(),"externalReference",reference(contract),"description","Assinatura mensal FixFlow")));
    }
    private void valid(String id,String prefix) { if(id==null || !id.matches(prefix+"_[a-zA-Z0-9]+")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Referência Asaas inválida."); }
    @Override public Recurrence retrieve(String id) { valid(id,"sub"); return recurrence(get("/subscriptions/"+id)); }
    @Override public void cancel(String id) {
        valid(id,"sub"); requireAvailable();
        try { client.delete().uri("/subscriptions/{id}",id).retrieve().toBodilessEntity(); }
        catch(HttpClientErrorException.NotFound ex) { /* Repeated deletion is complete. */ }
        catch(RestClientException ex) { throw failure(); }
    }
    private Payment parsePayment(JsonNode value) {
        if(!value.path("value").isNumber()) throw failure();
        String paid=value.path("paymentDate").asText("");
        String url=value.path("invoiceUrl").asText("");
        if(!url.matches("https://(?:[a-zA-Z0-9-]+\\.)*asaas\\.com(?:/[^\\s]*)?")) url=null;
        return new Payment(id(value,"pay"),value.path("subscription").asText(),value.path("customer").asText(),
            value.path("value").decimalValue(),LocalDate.parse(value.path("dueDate").asText()),value.path("status").asText(),url,
            paid.isBlank()?null:LocalDate.parse(paid).atStartOfDay(ZoneId.of("America/Sao_Paulo")).toInstant());
    }
    @Override public Payment payment(String id) { valid(id,"pay"); return parsePayment(get("/payments/"+id)); }
    @Override public List<Payment> payments(String subscription) {
        valid(subscription,"sub"); var result=new ArrayList<Payment>();
        for(int offset=0;offset<10000;offset+=100) {
            var response=get("/subscriptions/"+subscription+"/payments?limit=100&offset="+offset);
            if(!response.path("data").isArray()) throw failure();
            for(var node:response.path("data")) result.add(parsePayment(node));
            if(!response.path("hasMore").asBoolean(false)) return result;
        }
        throw failure();
    }
}
