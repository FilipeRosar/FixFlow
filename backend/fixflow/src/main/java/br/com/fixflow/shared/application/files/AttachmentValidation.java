package br.com.fixflow.shared.application.files;

public interface AttachmentValidation {
    int MAX_BYTES = 10 * 1024 * 1024;
    record Validated(String originalName, String extension, String contentType, byte[] content, String checksum, String sourceChecksum) { }
    Validated validate(String name, String contentType, byte[] content);
}
