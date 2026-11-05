package br.com.fixflow.shared.application.files;

import java.time.Instant;

public interface FileStorage {
    record Download(String url, Instant expiresAt) { }
    void put(String key, byte[] content, String contentType, String checksum);
    void delete(String key);
    Download download(String key, String contentType, String filename);
}
