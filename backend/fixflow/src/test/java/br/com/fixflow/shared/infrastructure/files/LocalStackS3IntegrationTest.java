package br.com.fixflow.shared.infrastructure.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Runs against the existing Compose service; never uses AWS credentials or a cloud endpoint. */
@EnabledIfEnvironmentVariable(named = "LOCALSTACK_TEST", matches = "true")
class LocalStackS3IntegrationTest {
    @Test void uploadsPrivatelyDownloadsWithBrowserEndpointAndRejectsInvalidSignatures() throws Exception {
        String endpoint = System.getenv().getOrDefault("LOCALSTACK_ENDPOINT", "http://localhost:4566");
        URI uri = URI.create(endpoint);
        assertTrue(Set.of("localhost", "127.0.0.1").contains(uri.getHost()), "Teste restrito ao LocalStack local.");
        String bucket = System.getenv().getOrDefault("S3_BUCKET", "fixflow-attachments");
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            String internalEndpoint = endpoint.replace("localhost", "127.0.0.1");
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("localstack-test", Map.of(
                    "fixflow.attachments.enabled", "true", "fixflow.attachments.bucket", bucket,
                    "fixflow.attachments.endpoint", internalEndpoint, "fixflow.attachments.public-endpoint", endpoint,
                    "fixflow.attachments.path-style", "true", "fixflow.attachments.access-key", "test", "fixflow.attachments.secret-key", "test")));
            context.register(AttachmentStorageConfiguration.class);
            context.refresh();
            var storage = context.getBean(br.com.fixflow.shared.application.files.FileStorage.class);
            String key = "tenants/" + UUID.randomUUID() + "/work-orders/" + UUID.randomUUID() + "/" + UUID.randomUUID() + ".png";
            var png = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", png);
            byte[] bytes = png.toByteArray();
            try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                try {
                    storage.put(key, bytes, "image/png", AttachmentValidator.sha256(bytes));
                    var signed = storage.download(key, "image/png", "foto.png");
                    assertTrue(signed.url().startsWith(endpoint + "/" + bucket + "/"));
                    assertTrue(signed.url().contains("X-Amz-Expires=300"));
                    var result = http.send(HttpRequest.newBuilder(URI.create(signed.url())).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                    assertEquals(200, result.statusCode());
                    assertArrayEquals(bytes, result.body());
                    assertEquals("image/png", result.headers().firstValue("content-type").orElseThrow());
                    assertTrue(result.headers().firstValue("content-disposition").orElseThrow().startsWith("attachment;"));
                    var anonymous = http.send(HttpRequest.newBuilder(URI.create(endpoint + "/" + bucket + "/" + key)).GET().build(), HttpResponse.BodyHandlers.discarding());
                    assertEquals(403, anonymous.statusCode(), "O objeto deve continuar privado.");
                    String tampered = signed.url().replaceAll("X-Amz-Signature=[a-f0-9]+", "X-Amz-Signature=" + "0".repeat(64));
                    var denied = http.send(HttpRequest.newBuilder(URI.create(tampered)).GET().build(), HttpResponse.BodyHandlers.discarding());
                    assertEquals(403, denied.statusCode(), "LocalStack deve conferir a assinatura.");
                } finally { storage.delete(key); }
            }
        }
    }
}
