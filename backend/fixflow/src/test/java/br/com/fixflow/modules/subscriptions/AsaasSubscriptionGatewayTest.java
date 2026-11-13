package br.com.fixflow.modules.subscriptions;

import br.com.fixflow.modules.subscriptions.infrastructure.AsaasSubscriptionGateway;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.web.server.ResponseStatusException;

class AsaasSubscriptionGatewayTest {
    HttpServer server;
    AsaasSubscriptionGateway gateway;
    final AtomicReference<String> response=new AtomicReference<>();
    final AtomicReference<String> body=new AtomicReference<>();
    final AtomicReference<String> path=new AtomicReference<>();
    final AtomicReference<String> method=new AtomicReference<>();
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            path.set(exchange.getRequestURI().toString());method.set(exchange.getRequestMethod());
            body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            assertEquals("api-test-key",exchange.getRequestHeaders().getFirst("access_token"));
            byte[] bytes=response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);
            exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.start();
        gateway=new AsaasSubscriptionGateway(true,"http://127.0.0.1:"+server.getAddress().getPort(),"api-test-key","01234567890123456789012345678901");
    }
    @AfterEach void close(){server.stop(0);}
    @Test void createsMonthlyPixWithOpaqueLocalReference() {
        UUID id=UUID.randomUUID();
        response.set("{\"id\":\"sub_test\",\"customer\":\"cus_test\",\"externalReference\":\"fixflow-subscription:"+id+"\",\"value\":69,\"cycle\":\"MONTHLY\",\"billingType\":\"PIX\"}");
        var result=gateway.create(id,"cus_test",new BigDecimal("69.00"),LocalDate.of(2026,11,9));
        assertEquals("/subscriptions",path.get());assertEquals("POST",method.get());
        assertTrue(body.get().contains("\"billingType\":\"PIX\""));assertTrue(body.get().contains("\"nextDueDate\":\"2026-11-09\""));
        assertEquals("fixflow-subscription:"+id,result.reference());
    }
    @Test void retrievesPaymentAndRejectsUnsafeInvoiceUrl() {
        response.set("{\"id\":\"pay_test\",\"subscription\":\"sub_test\",\"customer\":\"cus_test\",\"value\":69,\"dueDate\":\"2026-11-09\",\"status\":\"RECEIVED\",\"paymentDate\":\"2026-11-09\",\"invoiceUrl\":\"https://evil.invalid/pay\"}");
        var payment=gateway.payment("pay_test");
        assertEquals("/payments/pay_test",path.get());assertNull(payment.invoiceUrl());assertNotNull(payment.paidAt());
    }
    @Test void lookupRejectsDuplicateRemoteSubscriptionsAndWebhookRequiresSecret() {
        response.set("{\"data\":[{},{}],\"hasMore\":false}");
        assertThrows(ResponseStatusException.class,()->gateway.find(UUID.randomUUID()));
        assertThrows(ResponseStatusException.class,()->gateway.authenticate("wrong-secret"));
        assertDoesNotThrow(()->gateway.authenticate("01234567890123456789012345678901"));
    }
}
