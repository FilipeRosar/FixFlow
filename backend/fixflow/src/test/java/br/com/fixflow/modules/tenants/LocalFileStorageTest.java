package br.com.fixflow.modules.tenants;

import br.com.fixflow.modules.tenants.infrastructure.LocalFileStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class LocalFileStorageTest {
    @TempDir Path root;
    @Test void storesPrivateFilesAndRejectsOtherTenantsAndTraversal() {
        var files = new LocalFileStorage(root.toString());
        UUID tenant = UUID.randomUUID();
        byte[] bytes = {1, 2, 3};
        String key = files.storeLogo(tenant, bytes);
        assertTrue(key.startsWith("tenants/" + tenant + "/logos/"));
        assertArrayEquals(bytes, files.readLogo(tenant, key));
        assertThrows(IllegalArgumentException.class, () -> files.readLogo(UUID.randomUUID(), key));
        assertThrows(IllegalArgumentException.class, () -> files.readLogo(tenant, "../outside.png"));
        assertThrows(IllegalArgumentException.class, () -> files.deleteLogo(UUID.randomUUID(), key));
        assertArrayEquals(bytes, files.readLogo(tenant, key));
        files.deleteLogo(tenant, key);
        assertThrows(java.io.UncheckedIOException.class, () -> files.readLogo(tenant, key));
    }
}
