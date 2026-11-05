package br.com.fixflow.modules.tenants.infrastructure;

import br.com.fixflow.modules.tenants.application.FileStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;

@Component
public class LocalFileStorage implements FileStorage {
    private final Path root;
    public LocalFileStorage(@Value("${fixflow.files.root:./storage}") String root) { this.root = Path.of(root).toAbsolutePath().normalize(); }
    private Path path(UUID tenant, String key) {
        if (key == null || !key.matches("tenants/" + tenant + "/logos/[0-9a-f-]{36}\\.png"))
            throw new IllegalArgumentException("Referência de logo inválida.");
        return root.resolve(key);
    }
    @Override public String storeLogo(UUID tenant, byte[] png) {
        String key = "tenants/" + tenant + "/logos/" + UUID.randomUUID() + ".png";
        Path file = path(tenant, key);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, png, StandardOpenOption.CREATE_NEW);
            return key;
        } catch (IOException exception) { throw new UncheckedIOException(exception); }
    }
    @Override public byte[] readLogo(UUID tenant, String key) {
        try { return Files.readAllBytes(path(tenant, key)); }
        catch (IOException exception) { throw new UncheckedIOException(exception); }
    }
    @Override public void deleteLogo(UUID tenant, String key) {
        try { Files.deleteIfExists(path(tenant, key)); }
        catch (IOException exception) { throw new UncheckedIOException(exception); }
    }
}
