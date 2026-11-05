package br.com.fixflow.modules.tenants.infrastructure;

import br.com.fixflow.modules.tenants.application.CompanyConfiguration;
import br.com.fixflow.modules.tenants.application.FileStorage;
import br.com.fixflow.modules.tenants.application.TenantPolicies;
import br.com.fixflow.modules.tenants.domain.CompanySettings;
import br.com.fixflow.modules.tenants.domain.QuoteDefaults;
import br.com.fixflow.modules.identity.domain.RolePermissions;
import br.com.fixflow.shared.application.tenant.AuthorizedTenantOperations;
import br.com.fixflow.shared.application.tenant.AuthorizedMembership;
import br.com.fixflow.shared.application.audit.AuditService;
import br.com.fixflow.shared.infrastructure.security.CurrentAuthenticatedIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;
import java.time.Instant;
import java.math.BigDecimal;

@Service
public class CompanySettingsService implements CompanyConfiguration, TenantPolicies {
    private final JdbcTemplate jdbc;
    private final AuthorizedTenantOperations tenants;
    private final CurrentAuthenticatedIdentity identity;
    private final AuditService audit;
    private final FileStorage files;
    public CompanySettingsService(JdbcTemplate jdbc, AuthorizedTenantOperations tenants, CurrentAuthenticatedIdentity identity, AuditService audit, FileStorage files) {
        this.jdbc = jdbc; this.tenants = tenants; this.identity = identity; this.audit = audit; this.files = files;
    }
    private UUID currentTenant() {
        UUID tenant = identity.require().tenantId();
        if (tenant == null) throw new InsufficientAuthenticationException("Selecione uma empresa.");
        return tenant;
    }
    private void requireOwner(AuthorizedMembership member) {
        if (!RolePermissions.forRole(member.role().name()).contains("tenant:manage"))
            throw new AccessDeniedException("Owner obrigatório.");
    }
    private CompanySettings read(UUID tenant) { return read(tenant, true); }
    private CompanySettings read(UUID tenant, boolean includeLogo) {
        return jdbc.queryForObject("""
                SELECT s.logo_key, coalesce(s.warranty_terms, '') AS warranty_terms,
                       coalesce(s.quote_validity_days, 7) AS quote_validity_days,
                       coalesce(s.allow_negative_stock, false) AS allow_negative_stock
                FROM public.tenants t LEFT JOIN public.tenant_settings s ON s.tenant_id = t.id WHERE t.id = ?
                """, (rs, row) -> new CompanySettings(includeLogo ? files.logoDataUrl(tenant, rs.getString("logo_key")) : null, rs.getString("warranty_terms"),
                        rs.getInt("quote_validity_days"), rs.getBoolean("allow_negative_stock")), tenant);
    }
    @Override public CompanySettings load() {
        return tenants.execute(currentTenant(), member -> { requireOwner(member); return read(member.tenantId()); });
    }
    @Override public CompanySettings save(CompanySettings settings) {
        return tenants.execute(currentTenant(), member -> {
            requireOwner(member);
            String logoKey = jdbc.query("SELECT logo_key FROM public.tenant_settings WHERE tenant_id = ?",
                    rs -> rs.next() ? rs.getString("logo_key") : null, member.tenantId());
            String currentLogo = files.logoDataUrl(member.tenantId(), logoKey);
            if (!java.util.Objects.equals(currentLogo, settings.logoDataUrl())) {
                if (settings.logoDataUrl() == null) logoKey = null;
                else {
                    logoKey = files.storeLogo(member.tenantId(), sanitizeLogo(settings.logoDataUrl()));
                    String uploadedKey = logoKey;
                    org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                            new org.springframework.transaction.support.TransactionSynchronization() {
                                @Override public void afterCompletion(int status) {
                                    if (status != STATUS_COMMITTED) files.deleteLogo(member.tenantId(), uploadedKey);
                                }
                            });
                }
            }
            jdbc.update("""
                    INSERT INTO public.tenant_settings(tenant_id, logo_key, warranty_terms, quote_validity_days, allow_negative_stock)
                    VALUES (?, ?, ?, ?, ?) ON CONFLICT (tenant_id) DO UPDATE SET
                    logo_key = excluded.logo_key, warranty_terms = excluded.warranty_terms,
                    quote_validity_days = excluded.quote_validity_days, allow_negative_stock = excluded.allow_negative_stock,
                    updated_at = CURRENT_TIMESTAMP
                    """, member.tenantId(), logoKey, settings.warrantyTerms(), settings.quoteValidityDays(), settings.allowNegativeStock());
            audit.record(member.userId(), "TENANT_SETTINGS_UPDATED", "TENANT", member.tenantId(),
                    "{\"quoteValidityDays\":" + settings.quoteValidityDays() + ",\"allowNegativeStock\":" + settings.allowNegativeStock() + "}");
            return read(member.tenantId());
        });
    }
    @Override public QuoteDefaults quoteDefaults(UUID tenant, Instant issuedAt) {
        return tenants.execute(tenant, member -> read(member.tenantId()).quoteDefaults(issuedAt));
    }
    @Override public BigDecimal remainingStock(UUID tenant, BigDecimal balance, BigDecimal quantity) {
        return tenants.execute(tenant, member -> read(member.tenantId(), false).remainingStock(balance, quantity));
    }
    private byte[] sanitizeLogo(String value) {
        try {
            if (value.length() > 700000 || !value.matches("^data:image/(png|jpeg);base64,[A-Za-z0-9+/=]+$")) throw new IllegalArgumentException();
            byte[] bytes = java.util.Base64.getDecoder().decode(value.substring(value.indexOf(',') + 1));
            if (bytes.length > 524288) throw new IllegalArgumentException();
            try (var input = javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes))) {
                var readers = javax.imageio.ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw new IllegalArgumentException();
                var reader = readers.next();
                try {
                    reader.setInput(input);
                    String format = reader.getFormatName();
                    if (!(format.equalsIgnoreCase("png") && value.startsWith("data:image/png;"))
                            && !(format.equalsIgnoreCase("JPEG") && value.startsWith("data:image/jpeg;"))) throw new IllegalArgumentException();
                    if (reader.getWidth(0) > 2048 || reader.getHeight(0) > 2048) throw new IllegalArgumentException();
                    var image = reader.read(0);
                    if (image == null) throw new IllegalArgumentException();
                    var output = new java.io.ByteArrayOutputStream();
                    if (!javax.imageio.ImageIO.write(image, "png", output) || output.size() > 524288) throw new IllegalArgumentException();
                    return output.toByteArray();
                } finally { reader.dispose(); }
            }
        } catch (java.io.IOException | IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Envie um logo PNG ou JPEG de até 512 KB e 2048 × 2048 pixels.");
        }
    }
}
