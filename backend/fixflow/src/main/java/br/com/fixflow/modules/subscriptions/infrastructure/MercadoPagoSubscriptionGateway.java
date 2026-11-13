package br.com.fixflow.modules.subscriptions.infrastructure;

import br.com.fixflow.modules.subscriptions.application.SubscriptionGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@Service
@ConditionalOnProperty(name = "fixflow.subscriptions.provider", havingValue = "mercadopago", matchIfMissing = true)
public class MercadoPagoSubscriptionGateway implements SubscriptionGateway, br.com.fixflow.modules.subscriptions.application.MercadoPagoNotifications {
    private final RestClient client;
    private final boolean enabled;
    private final String token;
    private final String secret;
    private final String backUrl;
    private final String notificationUrl;

    public MercadoPagoSubscriptionGateway(
            @Value("${fixflow.subscriptions.enabled:false}") boolean enabled,
            @Value("${fixflow.subscriptions.mercadopago-base-url:https://api.mercadopago.com}") String baseUrl,
            @Value("${fixflow.subscriptions.mercadopago-access-token:}") String token,
            @Value("${fixflow.subscriptions.mercadopago-webhook-secret:}") String secret,
            @Value("${fixflow.subscriptions.mercadopago-back-url:}") String backUrl,
            @Value("${fixflow.subscriptions.mercadopago-notification-url:}") String notificationUrl) {
        this.enabled = enabled;
        this.token = token;
        this.secret = secret;
        this.backUrl = backUrl;
        this.notificationUrl = notificationUrl;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(8));
        client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + token).build();
    }

    private boolean https(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException ex) { return false; }
    }
    @Override public boolean available() {
        return enabled && !token.isBlank() && !secret.isBlank() && !token.equals(secret)
                && https(backUrl) && https(notificationUrl);
    }
    @Override public void requireAvailable() {
        if (!available()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Cobrança pelo Mercado Pago ainda não configurada. Entre em contato com o suporte.");
    }
    @Override public String provider() { return "mercadopago"; }
    @Override public boolean requiresDocument() { return false; }
    @Override public String billingType() { return "MERCADO_PAGO"; }
    @Override public void authenticate(String token) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Utilize a assinatura do webhook Mercado Pago.");
    }

    public void authenticateNotification(String signature, String requestId, String resourceId) {
        requireAvailable();
        if (signature == null || requestId == null || !requestId.matches("[a-zA-Z0-9-]{1,200}")
                || resourceId == null || !resourceId.matches("[a-zA-Z0-9]{1,100}")) throw unauthorized();
        Map<String, String> parts = new HashMap<>();
        for (String part : signature.split(",")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length != 2 || parts.put(pair[0], pair[1]) != null) throw unauthorized();
        }
        String timestamp = parts.get("ts"), digest = parts.get("v1");
        if (timestamp == null || !timestamp.matches("[0-9]{1,16}") || digest == null || !digest.matches("[a-fA-F0-9]{64}")) throw unauthorized();
        String manifest = "id:" + resourceId.toLowerCase(Locale.ROOT) + ";request-id:" + requestId + ";ts:" + timestamp + ";";
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            if (!MessageDigest.isEqual(mac.doFinal(manifest.getBytes(StandardCharsets.UTF_8)), HexFormat.of().parseHex(digest))) throw unauthorized();
        } catch (java.security.GeneralSecurityException ex) { throw new IllegalStateException(ex); }
    }
    private ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Webhook não autorizado.");
    }
    private ResponseStatusException failure() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Não foi possível confirmar a operação no Mercado Pago. Atualize a assinatura para conciliar a mesma solicitação.");
    }
    private JsonNode get(String path) {
        requireAvailable();
        try {
            JsonNode result = client.get().uri(path).retrieve().body(JsonNode.class);
            if (result == null) throw failure();
            return result;
        } catch (RestClientException ex) { throw failure(); }
    }
    private String raw(String id, String prefix) {
        if (id == null || !id.matches(prefix + "[a-zA-Z0-9]{1,80}")) throw failure();
        return id.substring(prefix.length());
    }
    private String id(JsonNode node) {
        String id = node.path("id").asText("");
        if (!id.matches("[a-zA-Z0-9]{1,80}")) throw failure();
        return id;
    }
    private String checkout(JsonNode node) {
        String url = node.path("init_point").asText("");
        if (!https(url)) return null;
        URI uri = URI.create(url);
        return Set.of("www.mercadopago.com.br", "mercadopago.com.br").contains(uri.getHost())
                && uri.getPath().startsWith("/subscriptions/checkout") && (uri.getPort() == -1 || uri.getPort() == 443) ? url : null;
    }
    private Recurrence recurrence(JsonNode node) {
        var recurring = node.path("auto_recurring");
        String reference = node.path("external_reference").asText("");
        if (!reference.matches("fixflow-subscription:[a-fA-F0-9-]{36}") || !recurring.path("transaction_amount").isNumber()
                || !"BRL".equals(recurring.path("currency_id").asText()) || recurring.path("frequency").asInt() != 1
                || !"months".equals(recurring.path("frequency_type").asText())) throw failure();
        String status = node.path("status").asText();
        if (!Set.of("pending", "authorized", "paused", "cancelled").contains(status)) throw failure();
        return new Recurrence("sub_mp" + id(node), reference, "mp", recurring.path("transaction_amount").decimalValue(),
                "MONTHLY", billingType(), "cancelled".equals(status), "pending".equals(status) ? checkout(node) : null);
    }
    // The checkout collects the payment method; no card data or remote customer is stored in FixFlow.
    @Override public Optional<Customer> findCustomer(UUID tenant) { return Optional.of(new Customer("mp", "fixflow-subscription:" + tenant, "")); }
    @Override public Customer createCustomer(UUID tenant, String name, String document, String email) {
        return new Customer("mp", "fixflow-subscription:" + tenant, document);
    }
    @Override public Optional<Recurrence> find(UUID contract) {
        String reference = "fixflow-subscription:" + contract;
        Recurrence match = null;
        for (int offset = 0; offset < 10000;) {
            // Search supports q, not external_reference; always verify the exact reference locally.
            var response = get("/preapproval/search?q=" + reference + "&limit=100&offset=" + offset);
            JsonNode results = response.path("results");
            if (!results.isArray() || !response.path("paging").path("total").isNumber()) throw failure();
            for (JsonNode result : results) {
                if (!result.has("external_reference")) throw failure();
                id(result);
                if (!reference.equals(result.path("external_reference").asText(""))) continue;
                Recurrence candidate = retrieve("sub_mp" + id(result));
                if (!reference.equals(candidate.reference()) || match != null) throw failure();
                match = candidate;
            }
            int received = results.size();
            if (offset + received >= response.path("paging").path("total").asInt()) return Optional.ofNullable(match);
            if (received == 0) throw failure();
            offset += received;
        }
        throw failure();
    }
    @Override public Recurrence create(UUID contract, String customer, BigDecimal amount, LocalDate firstDueDate) {
        throw new IllegalArgumentException("E-mail do pagador obrigatório.");
    }
    @Override public Recurrence create(UUID contract, String customer, String email, BigDecimal amount, LocalDate firstDueDate) {
        requireAvailable();
        if (email == null || email.isBlank()) throw failure();
        Map<String, Object> body = Map.of("reason", "FixFlow " + contract, "external_reference", "fixflow-subscription:" + contract,
                "payer_email", email, "auto_recurring", Map.of("frequency", 1, "frequency_type", "months", "transaction_amount", amount, "currency_id", "BRL"),
                "back_url", backUrl, "notification_url", notificationUrl, "status", "pending");
        try {
            JsonNode response = client.post().uri("/preapproval").header("X-Idempotency-Key", contract.toString())
                    .body(body).retrieve().body(JsonNode.class);
            if (response == null) throw failure();
            Recurrence result = recurrence(response);
            if (result.checkoutUrl() == null) throw failure();
            return result;
        } catch (RestClientResponseException ex) {
            if (Set.of(400, 401, 403, 404, 422).contains(ex.getStatusCode().value())) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn(
                    "Mercado Pago rejected subscription creation: contract={}, status={}", contract, ex.getStatusCode().value());
                if (ex.getResponseBodyAsString().contains("Both payer and collector must be real or test users"))
                    throw new SubscriptionGateway.CreationRejected(
                        "O Mercado Pago exige comprador e recebedor no mesmo ambiente. Em testes, use o e-mail de um comprador de teste; para clientes reais, configure as credenciais de produção.");
                throw new SubscriptionGateway.CreationRejected();
            }
            throw failure();
        } catch (RestClientException ex) { throw failure(); }
    }
    @Override public Recurrence retrieve(String id) { return recurrence(get("/preapproval/" + raw(id, "sub_mp"))); }
    @Override public void cancel(String id) {
        requireAvailable();
        String remote = raw(id, "sub_mp");
        if (retrieve(id).deleted()) return;
        try {
            JsonNode response = client.put().uri("/preapproval/{id}", remote).body(Map.of("status", "cancelled")).retrieve().body(JsonNode.class);
            if (response == null || !recurrence(response).deleted()) throw failure();
        } catch (RestClientException ex) { throw failure(); }
    }
    @Override public Payment payment(String id) {
        JsonNode invoice = get("/authorized_payments/" + raw(id, "pay_mp"));
        if (!("pay_mp" + id(invoice)).equals(id)) throw failure();
        String subscription = invoice.path("preapproval_id").asText("");
        if (!subscription.matches("[a-zA-Z0-9]{1,80}") || !invoice.path("transaction_amount").isNumber() && !invoice.path("transaction_amount").isTextual()) throw failure();
        BigDecimal amount;
        try { amount = new BigDecimal(invoice.path("transaction_amount").asText()); }
        catch (NumberFormatException ex) { throw failure(); }
        if (!"BRL".equals(invoice.path("currency_id").asText()) || amount.signum() <= 0) throw failure();
        String status = "PENDING";
        Instant paid = null;
        String paymentId = invoice.path("payment").path("id").asText("");
        if (!paymentId.isBlank() && !"0".equals(paymentId)) {
            if (!paymentId.matches("[0-9]{1,80}")) throw failure();
            JsonNode payment = get("/v1/payments/" + paymentId);
            if (!paymentId.equals(id(payment)) || !"BRL".equals(payment.path("currency_id").asText())
                    || !payment.path("transaction_amount").isNumber() || amount.compareTo(payment.path("transaction_amount").decimalValue()) != 0) throw failure();
            status = switch (payment.path("status").asText()) {
                case "approved" -> "CONFIRMED";
                case "refunded" -> "REFUNDED";
                case "charged_back" -> "CHARGED_BACK";
                case "cancelled" -> "DELETED";
                case "rejected" -> "REJECTED";
                default -> "PENDING";
            };
            if ("CONFIRMED".equals(status)) {
                String approved = payment.path("date_approved").asText("");
                if (approved.isBlank() || payment.path("transaction_amount_refunded").asDouble(0) > 0) throw failure();
                paid = OffsetDateTime.parse(approved).toInstant();
            }
        }
        String debit = invoice.path("debit_date").asText("");
        if (debit.isBlank()) throw failure();
        LocalDate due = OffsetDateTime.parse(debit).atZoneSameInstant(ZoneId.of("America/Sao_Paulo")).toLocalDate();
        return new Payment(id, "sub_mp" + subscription, "mp", amount, due, status, null, paid);
    }
    @Override public List<Payment> payments(String subscription) {
        String remote = raw(subscription, "sub_mp");
        List<Payment> results = new ArrayList<>();
        for (int offset = 0; offset < 10000;) {
            var response = get("/authorized_payments/search?preapproval_id=" + remote + "&limit=10&offset=" + offset);
            if (!response.path("results").isArray() || !response.path("paging").path("total").isNumber()) throw failure();
            for (JsonNode node : response.path("results")) {
                Payment payment = payment("pay_mp" + id(node));
                if (!subscription.equals(payment.subscription())) throw failure();
                results.add(payment);
            }
            int received = response.path("results").size();
            if (offset + received >= response.path("paging").path("total").asInt()) return results;
            if (received == 0) throw failure();
            offset += received;
        }
        throw failure();
    }
}
