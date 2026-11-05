package br.com.fixflow.shared.application.files;

import java.time.Instant;

/** Private attachment objects. Keys and content types originate exclusively on the server. */
public interface FileStorage {
    record Download(String url, Instant expiresAt) { }
    void put(String key, byte[] content, String contentType, String checksum);
    void delete(String key);
    Download download(String key, String contentType, String filename);
}
