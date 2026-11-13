package br.com.fixflow.modules.subscriptions;

import br.com.fixflow.modules.subscriptions.infrastructure.MercadoPagoSubscriptionGateway;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.jupiter.api.Assertions.*;

class MercadoPagoSubscriptionGatewayTest {
    HttpServer server;
    MercadoPagoSubscriptionGateway gateway;
    final Map<String,String> responses = new ConcurrentHashMap<>();
    final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    String body;
    String idempotency;
    int creationStatus = 200;
    final UUID contract = UUID.fromString("c7ae5517-a860-49e3-8950-f44761b10ced");
    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().toString();
            calls.add(exchange.getRequestMethod() + " " + path);
            body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            idempotency = exchange.getRequestHeaders().getFirst("X-Idempotency-Key");
            String response = responses.get(path);
            boolean allowed = "Bearer access-test".equals(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = (response == null ? "{}" : response).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(!allowed ? 401 : response == null ? 404 : path.equals("/preapproval") ? creationStatus : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        gateway = new MercadoPagoSubscriptionGateway(true, "http://127.0.0.1:" + server.getAddress().getPort(),
                "access-test", "secret-test", "https://fixflow.example/inicio/configuracoes/assinatura?assinatura=retorno",
                "https://fixflow.example/webhooks/mercadopago/subscriptions");
    }
    @AfterEach void close() { server.stop(0); }
    String recurrence(String state, String url) {
        return """
            {"id":"abc123","external_reference":"fixflow-subscription:%s","status":"%s",
             "init_point":"%s","auto_recurring":{"frequency":1,"frequency_type":"months","transaction_amount":69.90,"currency_id":"BRL"}}
            """.formatted(contract, state, url);
    }
    void invoice(String status, String currency, String amount) {
        responses.put("/authorized_payments/123", """
            {"id":123,"preapproval_id":"abc123","currency_id":"BRL","transaction_amount":"69.90",
             "debit_date":"2026-11-09T13:00:00Z","payment":{"id":456}}
            """);
        responses.put("/v1/payments/456", """
            {"id":456,"status":"%s","currency_id":"%s","transaction_amount":%s,
             "transaction_amount_refunded":0,"date_approved":"2026-11-09T13:01:00Z"}
            """.formatted(status, currency, amount));
    }
    @Test void recoversCheckoutByExactExternalReference() {
        String path = "/preapproval/search?q=fixflow-subscription:" + contract + "&limit=100&offset=0";
        responses.put(path, "{\"paging\":{\"total\":1},\"results\":[{\"id\":\"abc123\",\"external_reference\":\"fixflow-subscription:" + contract + "\"}]}");
        responses.put("/preapproval/abc123", recurrence("pending", "https://www.mercadopago.com.br/subscriptions/checkout?preapproval_id=abc123"));
        assertEquals("sub_mpabc123", gateway.find(contract).orElseThrow().id());
        assertTrue(calls.contains("GET " + path));
        assertFalse(calls.stream().anyMatch(call -> call.startsWith("POST")));
    }
    @Test void ignoresForeignSearchResults() {
        responses.put("/preapproval/search?q=fixflow-subscription:" + contract + "&limit=100&offset=0",
                "{\"paging\":{\"total\":1},\"results\":[{\"id\":\"foreign\",\"external_reference\":\"fixflow-subscription:" + UUID.randomUUID() + "\"}]}");
        assertTrue(gateway.find(contract).isEmpty());
        assertEquals(1, calls.size());
    }
    @Test void findsExactReferenceOnLaterPageAndRejectsDuplicates() {
        String path = "/preapproval/search?q=fixflow-subscription:" + contract + "&limit=100&offset=";
        String exact = "{\"id\":\"abc123\",\"external_reference\":\"fixflow-subscription:" + contract + "\"}";
        responses.put(path + "0", "{\"paging\":{\"total\":2},\"results\":[{\"id\":\"foreign\",\"external_reference\":\"other\"}]}");
        responses.put(path + "1", "{\"paging\":{\"total\":2},\"results\":[" + exact + "]}");
        responses.put("/preapproval/abc123", recurrence("pending", "https://www.mercadopago.com.br/subscriptions/checkout?preapproval_id=abc123"));
        assertEquals("sub_mpabc123", gateway.find(contract).orElseThrow().id());
        responses.put(path + "0", "{\"paging\":{\"total\":2},\"results\":[" + exact + "]}");
        assertThrows(ResponseStatusException.class, () -> gateway.find(contract));
    }
    @Test void createsPendingMonthlyBrlCheckoutWithStableIdempotency() {
        responses.put("/preapproval", recurrence("pending", "https://www.mercadopago.com.br/subscriptions/checkout?preapproval_id=abc123"));
        var remote = gateway.create(contract, "mp", "owner@example.com", new BigDecimal("69.90"), LocalDate.of(2026,11,9));
        assertEquals("sub_mpabc123", remote.id());
        assertNotNull(remote.checkoutUrl());
        assertEquals(contract.toString(), idempotency);
        var request = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertTrue(request.path("reason").asText().length() <= 60);
        assertEquals("fixflow-subscription:" + contract, request.path("external_reference").asText());
        assertTrue(body.contains("\"status\":\"pending\""));
        assertTrue(body.contains("\"payer_email\":\"owner@example.com\""));
        assertTrue(body.contains("\"currency_id\":\"BRL\""));
        assertTrue(body.contains("\"notification_url\""));
        assertFalse(gateway.requiresDocument());
    }
    @Test void preservesExactPlanPricesFromProvider() {
        for (String price : List.of("69.90", "119.90", "199.90")) {
            responses.put("/preapproval", recurrence("pending", "https://www.mercadopago.com.br/subscriptions/checkout?preapproval_id=abc123")
                    .replace("69.90", price));
            var remote = gateway.create(contract, "mp", "owner@example.com", new BigDecimal(price), LocalDate.of(2026, 11, 9));
            assertEquals(0, new BigDecimal(price).compareTo(remote.amount()), "Provider amount for " + price);
        }
    }
    @Test void distinguishesDefinitiveRejectionFromAmbiguousFailures() {
        responses.put("/preapproval", "{\"message\":\"Invalid request\"}");
        creationStatus = 400;
        assertThrows(br.com.fixflow.modules.subscriptions.application.SubscriptionGateway.CreationRejected.class,
            () -> gateway.create(contract,"mp","owner@example.com",BigDecimal.TEN,LocalDate.now()));
        responses.put("/preapproval", "{\"message\":\"Both payer and collector must be real or test users\"}");
        var mismatch = assertThrows(br.com.fixflow.modules.subscriptions.application.SubscriptionGateway.CreationRejected.class,
            () -> gateway.create(contract,"mp","owner@example.com",BigDecimal.TEN,LocalDate.now()));
        assertTrue(mismatch.getReason().contains("comprador de teste"));
        creationStatus = 500;
        var failure = assertThrows(ResponseStatusException.class,
            () -> gateway.create(contract,"mp","owner@example.com",BigDecimal.TEN,LocalDate.now()));
        assertFalse(failure instanceof br.com.fixflow.modules.subscriptions.application.SubscriptionGateway.CreationRejected);
    }
    @Test void rejectsUntrustedCheckoutHost() {
        responses.put("/preapproval", recurrence("pending", "https://www.mercadopago.com.br.evil.example/subscriptions/checkout"));
        assertThrows(ResponseStatusException.class, () -> gateway.create(contract,"mp","owner@example.com", BigDecimal.TEN,LocalDate.now()));
    }
    @Test void verifiesPaymentAgainstApiAndAcceptsStringInvoiceAmount() {
        invoice("approved", "BRL", "69.90");
        var payment = gateway.payment("pay_mp123");
        assertEquals("CONFIRMED", payment.status());
        assertEquals("sub_mpabc123", payment.subscription());
        assertEquals(new BigDecimal("69.90"), payment.amount());
        assertNotNull(payment.paidAt());
        assertEquals(LocalDate.of(2026,11,9), payment.dueDate());
        assertNull(payment.invoiceUrl());
        assertTrue(calls.contains("GET /v1/payments/456"));
    }
    @Test void refusesWrongCurrencyAndAmount() {
        invoice("approved", "USD", "69.90");
        assertThrows(ResponseStatusException.class, () -> gateway.payment("pay_mp123"));
        invoice("approved", "BRL", "1.00");
        assertThrows(ResponseStatusException.class, () -> gateway.payment("pay_mp123"));
    }
    @Test void pendingPaymentDoesNotGrantAccessAndRefundIsReported() {
        invoice("pending", "BRL", "69.90");
        assertEquals("PENDING", gateway.payment("pay_mp123").status());
        invoice("refunded", "BRL", "69.90");
        assertEquals("REFUNDED", gateway.payment("pay_mp123").status());
    }
    @Test void pendingCheckoutWithoutInvoicesUsesAcceptedSearchLimit() {
        responses.put("/authorized_payments/search?preapproval_id=abc123&limit=10&offset=0",
                "{\"paging\":{\"offset\":0,\"limit\":10,\"total\":0},\"results\":[]}");
        assertTrue(gateway.payments("sub_mpabc123").isEmpty());
        assertEquals(List.of("GET /authorized_payments/search?preapproval_id=abc123&limit=10&offset=0"), calls);
    }
    @Test void paginatesUsingApiPageSizeAndRejectsForeignInvoice() {
        responses.put("/authorized_payments/search?preapproval_id=abc123&limit=10&offset=0",
                "{\"paging\":{\"offset\":0,\"limit\":1,\"total\":2},\"results\":[{\"id\":123}]}");
        responses.put("/authorized_payments/search?preapproval_id=abc123&limit=10&offset=1",
                "{\"paging\":{\"offset\":1,\"limit\":1,\"total\":2},\"results\":[{\"id\":124}]}");
        invoice("approved", "BRL", "69.90");
        responses.put("/authorized_payments/124", responses.get("/authorized_payments/123").replace("\"id\":123", "\"id\":124"));
        assertEquals(2, gateway.payments("sub_mpabc123").size());
        responses.put("/authorized_payments/124", responses.get("/authorized_payments/124").replace("abc123", "foreign"));
        assertThrows(ResponseStatusException.class, () -> gateway.payments("sub_mpabc123"));
    }
    @Test void cancelIsIdempotentForAlreadyCancelledSubscription() {
        responses.put("/preapproval/abc123", recurrence("cancelled", ""));
        gateway.cancel("sub_mpabc123");
        assertEquals(List.of("GET /preapproval/abc123"), calls);
    }
    @Test void validatesWebhookHmacAndRejectsTampering() throws Exception {
        String manifest = "id:abc123;request-id:request-123;ts:1794258000;";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("secret-test".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = "ts=1794258000,v1=" + HexFormat.of().formatHex(mac.doFinal(manifest.getBytes(StandardCharsets.UTF_8)));
        assertDoesNotThrow(() -> gateway.authenticateNotification(signature,"request-123","ABC123"));
        assertThrows(ResponseStatusException.class, () -> gateway.authenticateNotification(signature,"request-123","abc124"));
        assertThrows(ResponseStatusException.class, () -> gateway.authenticateNotification(signature,"request-124","abc123"));
        assertThrows(ResponseStatusException.class, () -> gateway.authenticateNotification(null,"request-123","abc123"));
    }
    @Test void duplicateLookupAndMalformedIdsFailClosed() {
        responses.put("/preapproval/search?q=fixflow-subscription:" + contract + "&limit=100&offset=0", "{\"paging\":{\"total\":2},\"results\":[{},{}]}");
        assertThrows(ResponseStatusException.class, () -> gateway.find(contract));
        assertThrows(ResponseStatusException.class, () -> gateway.payment("pay_mp../../elsewhere"));
    }
}
