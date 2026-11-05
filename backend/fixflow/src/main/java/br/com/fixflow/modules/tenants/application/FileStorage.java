package br.com.fixflow.modules.tenants.application;
import java.util.UUID;
/** Private tenant files. Only server-generated keys may cross this boundary. */
public interface FileStorage {
    String storeLogo(UUID tenantId, byte[] png);
    byte[] readLogo(UUID tenantId, String key);
    void deleteLogo(UUID tenantId, String key);
    default String logoDataUrl(UUID tenantId, String key) {
        return key == null ? null : "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(readLogo(tenantId, key));
    }
}
