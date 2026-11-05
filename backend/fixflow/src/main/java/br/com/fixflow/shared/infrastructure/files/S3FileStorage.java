package br.com.fixflow.shared.infrastructure.files;

import br.com.fixflow.shared.application.files.FileStorage;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.core.exception.SdkException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;

public class S3FileStorage implements FileStorage {
    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    public S3FileStorage(S3Client s3, S3Presigner presigner, String bucket) {
        if (bucket == null || bucket.isBlank()) throw new IllegalArgumentException("Configure S3_BUCKET para habilitar anexos.");
        this.s3 = s3; this.presigner = presigner; this.bucket = bucket;
    }
    private void key(String key) {
        if (key == null || !key.matches("tenants/[0-9a-f-]{36}/work-orders/[0-9a-f-]{36}/[0-9a-f-]{36}\\.(jpg|png|pdf)"))
            throw new IllegalArgumentException("Referência de anexo inválida.");
    }
    private void requirePrivateBucket() {
        var block = s3.getPublicAccessBlock(GetPublicAccessBlockRequest.builder().bucket(bucket).build()).publicAccessBlockConfiguration();
        if (block == null || !Boolean.TRUE.equals(block.blockPublicAcls()) || !Boolean.TRUE.equals(block.ignorePublicAcls())
                || !Boolean.TRUE.equals(block.blockPublicPolicy()) || !Boolean.TRUE.equals(block.restrictPublicBuckets()))
            throw unavailable();
    }
    @Override public void put(String key, byte[] content, String contentType, String checksum) {
        key(key);
        try {
            requirePrivateBucket();
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType)
                    .contentLength((long) content.length).serverSideEncryption(ServerSideEncryption.AES256)
                    .checksumSHA256(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(checksum)))
                    .contentDisposition("attachment").cacheControl("private, no-store").build(), RequestBody.fromBytes(content));
        } catch (SdkException exception) { throw unavailable(exception); }
    }
    @Override public void delete(String key) {
        key(key);
        // An idempotent compensating delete must remain possible even if bucket privacy was misconfigured.
        try { s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build()); }
        catch (SdkException exception) { throw unavailable(exception); }
    }
    @Override public Download download(String key, String contentType, String filename) {
        key(key);
        try {
            requirePrivateBucket();
            var request = GetObjectRequest.builder().bucket(bucket).key(key).responseContentType(contentType)
                    .responseContentDisposition(ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
                    .responseCacheControl("private, no-store").build();
            var signed = presigner.presignGetObject(GetObjectPresignRequest.builder().signatureDuration(Duration.ofMinutes(5)).getObjectRequest(request).build());
            return new Download(signed.url().toExternalForm(), signed.expiration());
        } catch (SdkException exception) { throw unavailable(exception); }
    }
    private ResponseStatusException unavailable() { return unavailable(null); }
    private ResponseStatusException unavailable(Throwable cause) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Armazenamento privado indisponível. Verifique a configuração do S3.", cause);
    }
}
