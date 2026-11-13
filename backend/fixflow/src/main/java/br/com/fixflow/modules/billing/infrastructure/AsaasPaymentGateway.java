package br.com.fixflow.modules.billing.infrastructure;

import br.com.fixflow.modules.billing.application.PaymentGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

@Service
public class AsaasPaymentGateway implements PaymentGateway {
    private final RestClient client;
    private final boolean enabled;
    private final String apiKey;
    private final String webhookToken;
    public AsaasPaymentGateway(@Value("${fixflow.asaas.enabled:false}") boolean enabled,
            @Value("${fixflow.asaas.base-url:https://api-sandbox.asaas.com/v3}") String baseUrl,
            @Value("${fixflow.asaas.api-key:}") String apiKey, @Value("${fixflow.asaas.webhook-token:}") String token) {
        this.enabled = enabled; this.apiKey = apiKey; this.webhookToken = token;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader("access_token", apiKey).defaultHeader("User-Agent", "FixFlow/1.0").build();
    }
    @Override public void requireAvailable() {
        if (!enabled || apiKey.isBlank() || !webhookToken.matches("\\S{32,255}") || apiKey.equals(webhookToken))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Integração Asaas não configurada.");
    }
    @Override public void authenticateWebhook(String token) {
        requireAvailable();
        if (token == null || !MessageDigest.isEqual(webhookToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Webhook não autorizado.");
    }
    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Não foi possível confirmar a operação no Asaas. Repita com a mesma chave.");
    }
    private RemotePayment payment(JsonNode node) {
        if (node == null || !node.path("id").asText().matches("pay_[a-zA-Z0-9]+") || !node.path("value").isNumber()) throw unavailable();
        String date = node.path("paymentDate").asText("");
        return new RemotePayment(node.path("id").asText(), node.path("externalReference").asText(), node.path("customer").asText(),
                node.path("billingType").asText(), node.path("status").asText(), node.path("value").decimalValue(), date.isBlank() ? null : LocalDate.parse(date));
    }
    @Override public RemotePayment createPix(PixRequest request) {
        requireAvailable();
        try {
            var customer = client.get().uri("/customers/{id}", request.customer()).retrieve().body(JsonNode.class);
            if (customer == null || !request.customer().equals(customer.path("id").asText()) || customer.path("deleted").asBoolean(false)
                    || !request.document().equals(customer.path("cpfCnpj").asText().replaceAll("[^0-9]", "")))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Cliente Asaas não corresponde ao documento do cliente da OS.");
            return payment(client.post().uri("/payments").body(Map.of("customer", request.customer(), "billingType", "PIX",
                    "value", request.amount(), "dueDate", request.dueDate().toString(), "externalReference", request.reference().toString(),
                    "description", "Ordem de serviço FixFlow")).retrieve().body(JsonNode.class));
        } catch (RestClientException ex) { throw unavailable(); }
    }
    @Override public Optional<RemotePayment> findPix(UUID reference) {
        requireAvailable();
        try {
            var body = client.get().uri(uri -> uri.path("/payments").queryParam("externalReference", reference).queryParam("limit", 2).build())
                    .retrieve().body(JsonNode.class);
            if (body == null || !body.path("data").isArray() || body.path("data").size() > 1 || body.path("hasMore").asBoolean(false)) throw unavailable();
            return body.path("data").isEmpty() ? Optional.empty() : Optional.of(payment(body.path("data").get(0)));
        } catch (RestClientException ex) { throw unavailable(); }
    }
    @Override public RemotePayment retrieve(String id) {
        requireAvailable();
        if (id == null || !id.matches("pay_[a-zA-Z0-9]+")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pagamento Asaas inválido.");
        try { return payment(client.get().uri("/payments/{id}", id).retrieve().body(JsonNode.class)); }
        catch (RestClientException ex) { throw unavailable(); }
    }
    @Override public PixCode qrCode(String id) {
        requireAvailable();
        try {
            var body = client.get().uri("/payments/{id}/pixQrCode", id).retrieve().body(JsonNode.class);
            if (body == null || body.path("payload").asText().isBlank() || body.path("encodedImage").asText().isBlank()) throw unavailable();
            return new PixCode(body.path("encodedImage").asText(), body.path("payload").asText(), body.path("expirationDate").asText());
        } catch (RestClientException ex) { throw unavailable(); }
    }
}
