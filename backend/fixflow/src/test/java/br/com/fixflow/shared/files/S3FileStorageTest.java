package br.com.fixflow.shared.files;

import br.com.fixflow.shared.infrastructure.files.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.core.sync.RequestBody;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class S3FileStorageTest {
    S3Client s3;
    S3Presigner presigner;
    S3FileStorage storage;
    final String key = "tenants/" + UUID.randomUUID() + "/work-orders/" + UUID.randomUUID() + "/" + UUID.randomUUID() + ".png";
    @BeforeEach void setup() {
        s3 = mock(S3Client.class);
        presigner = S3Presigner.builder().region(Region.SA_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test-key", "test-secret"))).build();
        storage = new S3FileStorage(s3, presigner, "private-test-bucket");
        when(s3.getPublicAccessBlock(any(GetPublicAccessBlockRequest.class))).thenReturn(GetPublicAccessBlockResponse.builder()
                .publicAccessBlockConfiguration(PublicAccessBlockConfiguration.builder().blockPublicAcls(true).ignorePublicAcls(true).blockPublicPolicy(true).restrictPublicBuckets(true).build()).build());
    }
    @AfterEach void close() { presigner.close(); }
    @Test void uploadsWithEncryptionChecksumAndNoPublicAcl() throws Exception {
        byte[] bytes = {1, 2, 3};
        storage.put(key, bytes, "image/png", AttachmentValidator.sha256(bytes));
        var request = ArgumentCaptor.forClass(PutObjectRequest.class);
        var body = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3).putObject(request.capture(), body.capture());
        assertEquals(ServerSideEncryption.AES256, request.getValue().serverSideEncryption());
        assertEquals("image/png", request.getValue().contentType());
        assertNull(request.getValue().acl());
        assertNotNull(request.getValue().checksumSHA256());
        try (var input = body.getValue().contentStreamProvider().newStream()) { assertArrayEquals(bytes, input.readAllBytes()); }
    }
    @Test void producesActualSigV4UrlWithFiveMinuteExpiryAndFixedDownloadHeaders() {
        var before = Instant.now();
        var signed = storage.download(key, "image/png", "foto.png");
        assertTrue(signed.url().startsWith("https://private-test-bucket.s3.sa-east-1.amazonaws.com/tenants/"));
        assertTrue(signed.url().contains("X-Amz-Expires=300"));
        assertTrue(signed.url().contains("X-Amz-Signature="));
        assertTrue(signed.url().contains("response-content-disposition="));
        assertTrue(signed.url().contains("response-content-type=image%2Fpng"));
        assertTrue(signed.expiresAt().isAfter(before.plusSeconds(295)));
        assertFalse(signed.expiresAt().isAfter(Instant.now().plusSeconds(301)));
    }
    @Test void refusesBucketWithoutAllFourPublicAccessBlocks() {
        when(s3.getPublicAccessBlock(any(GetPublicAccessBlockRequest.class))).thenReturn(GetPublicAccessBlockResponse.builder()
                .publicAccessBlockConfiguration(PublicAccessBlockConfiguration.builder().blockPublicAcls(true).build()).build());
        assertEquals(503, assertThrows(ResponseStatusException.class, () -> storage.put(key, new byte[]{1}, "image/png", "00".repeat(32))).getStatusCode().value());
        assertThrows(ResponseStatusException.class, () -> storage.download(key, "image/png", "file.png"));
        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }
    @Test void rejectsNonCanonicalKeys() {
        assertThrows(IllegalArgumentException.class, () -> storage.download("../foreign/file.png", "image/png", "file.png"));
        verifyNoInteractions(s3);
    }
}
