package br.com.fixflow.shared.infrastructure.files;

import br.com.fixflow.shared.application.files.FileStorage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.services.s3.S3Configuration;
import java.net.URI;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import java.time.Duration;

@Configuration
public class AttachmentStorageConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "fixflow.attachments.enabled", havingValue = "true")
    S3Client attachmentS3(@Value("${fixflow.attachments.region:sa-east-1}") String region,
                          @Value("${fixflow.attachments.endpoint:}") String endpoint,
                          @Value("${fixflow.attachments.path-style:false}") boolean pathStyle,
                          AwsCredentialsProvider attachmentCredentials) {
        var builder = S3Client.builder().region(Region.of(region)).credentialsProvider(attachmentCredentials)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build())
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(15)))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(30)).apiCallAttemptTimeout(Duration.ofSeconds(15)));
        if (!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        return builder.build();
    }
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "fixflow.attachments.enabled", havingValue = "true")
    S3Presigner attachmentPresigner(@Value("${fixflow.attachments.region:sa-east-1}") String region,
                                    @Value("${fixflow.attachments.endpoint:}") String endpoint,
                                    @Value("${fixflow.attachments.public-endpoint:}") String publicEndpoint,
                                    @Value("${fixflow.attachments.path-style:false}") boolean pathStyle,
                                    AwsCredentialsProvider attachmentCredentials) {
        var builder = S3Presigner.builder().region(Region.of(region)).credentialsProvider(attachmentCredentials)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build());
        String signingEndpoint = publicEndpoint.isBlank() ? endpoint : publicEndpoint;
        if (!signingEndpoint.isBlank()) builder.endpointOverride(URI.create(signingEndpoint));
        return builder.build();
    }
    @Bean
    @ConditionalOnProperty(name = "fixflow.attachments.enabled", havingValue = "true")
    AwsCredentialsProvider attachmentCredentials(@Value("${fixflow.attachments.access-key:}") String accessKey,
                                                 @Value("${fixflow.attachments.secret-key:}") String secretKey) {
        if (accessKey.isBlank() && secretKey.isBlank()) return DefaultCredentialsProvider.builder().build();
        if (accessKey.isBlank() || secretKey.isBlank()) throw new IllegalArgumentException("Informe as duas credenciais do armazenamento S3.");
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
    }
    @Bean
    @ConditionalOnProperty(name = "fixflow.attachments.enabled", havingValue = "true")
    FileStorage attachmentStorage(S3Client attachmentS3, S3Presigner attachmentPresigner, @Value("${fixflow.attachments.bucket:}") String bucket) {
        return new S3FileStorage(attachmentS3, attachmentPresigner, bucket);
    }
    @Bean
    @ConditionalOnProperty(name = "fixflow.attachments.enabled", havingValue = "false", matchIfMissing = true)
    FileStorage unavailableAttachmentStorage() {
        return new FileStorage() {
            private ResponseStatusException unavailable() { return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Armazenamento de anexos não configurado. Configure o bucket S3 privado."); }
            @Override public void put(String key, byte[] content, String contentType, String checksum) { throw unavailable(); }
            @Override public void delete(String key) { }
            @Override public Download download(String key, String contentType, String filename) { throw unavailable(); }
        };
    }
}
