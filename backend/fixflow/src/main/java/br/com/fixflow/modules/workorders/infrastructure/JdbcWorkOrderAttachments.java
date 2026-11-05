package br.com.fixflow.modules.workorders.infrastructure;

import br.com.fixflow.modules.workorders.application.WorkOrderAttachments;
import br.com.fixflow.modules.workorders.domain.WorkOrderAttachment;
import br.com.fixflow.modules.identity.domain.RolePermissions;
import br.com.fixflow.shared.application.files.*;
import br.com.fixflow.shared.application.tenant.*;
import br.com.fixflow.shared.application.audit.AuditService;
import br.com.fixflow.shared.infrastructure.security.CurrentAuthenticatedIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

@Service
public class JdbcWorkOrderAttachments implements WorkOrderAttachments {
    private static final Logger log = LoggerFactory.getLogger(JdbcWorkOrderAttachments.class);
    private final JdbcTemplate jdbc;
    private final AuthorizedTenantOperations tenants;
    private final CurrentAuthenticatedIdentity identity;
    private final AttachmentValidation validation;
    private final FileStorage storage;
    private final AuditService audit;
    private final ObjectMapper mapper;
    public JdbcWorkOrderAttachments(JdbcTemplate jdbc, AuthorizedTenantOperations tenants, CurrentAuthenticatedIdentity identity,
                                    AttachmentValidation validation, FileStorage storage, AuditService audit, ObjectMapper mapper) {
        this.jdbc = jdbc; this.tenants = tenants; this.identity = identity; this.validation = validation;
        this.storage = storage; this.audit = audit; this.mapper = mapper;
    }
    private UUID tenant() {
        UUID tenant = identity.require().tenantId();
        if (tenant == null) throw new InsufficientAuthenticationException("Selecione uma empresa.");
        return tenant;
    }
    private static final RowMapper<WorkOrderAttachment> ROW = (rs, row) -> new WorkOrderAttachment(rs.getObject("id", UUID.class),
            rs.getString("original_name"), rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("checksum"),
            rs.getObject("uploaded_by", UUID.class), rs.getTimestamp("created_at").toInstant());
    private void authorize(AuthorizedMembership m, UUID order, boolean writing) {
        var permissions = RolePermissions.forRole(m.role().name());
        if (writing ? !permissions.contains("workorder:attach") : !permissions.contains("workorder:read") && !permissions.contains("workorder:read_assigned"))
            throw new AccessDeniedException("Acesso aos anexos não autorizado.");
        var rows = jdbc.queryForList("SELECT id FROM public.work_orders WHERE tenant_id = ? AND id = ? FOR UPDATE", m.tenantId(), order);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "OS não encontrada.");
        if (m.role().name().equals("TECHNICIAN") && !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM public.work_order_transition_facts WHERE tenant_id = ? AND work_order_id = ? AND technician_id = ?)", Boolean.class, m.tenantId(), order, m.userId())))
            throw new AccessDeniedException("OS não atribuída a este técnico.");
    }
    private List<WorkOrderAttachment> load(UUID tenant, UUID order) {
        return jdbc.query("SELECT * FROM public.work_order_attachments WHERE tenant_id = ? AND work_order_id = ? ORDER BY created_at DESC, id", ROW, tenant, order);
    }
    @Override public List<WorkOrderAttachment> list(UUID order) {
        return tenants.execute(tenant(), m -> { authorize(m, order, false); return load(m.tenantId(), order); });
    }
    @Override public WorkOrderAttachment upload(UUID order, UUID uploadId, String name, String contentType, byte[] content) {
        return tenants.execute(tenant(), m -> {
            authorize(m, order, true);
            var validated = validation.validate(name, contentType, content);
            var existing = jdbc.queryForList("SELECT id, source_checksum, original_name FROM public.work_order_attachments WHERE tenant_id = ? AND work_order_id = ? AND upload_id = ?", m.tenantId(), order, uploadId);
            if (!existing.isEmpty()) {
                var old = existing.getFirst();
                if (!validated.sourceChecksum().equals(old.get("source_checksum")) || !validated.originalName().equals(old.get("original_name")))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Identificador de upload já utilizado para outro arquivo.");
                return load(m.tenantId(), order).stream().filter(a -> a.id().equals(old.get("id"))).findFirst().orElseThrow();
            }
            int count = jdbc.queryForObject("SELECT count(*) FROM public.work_order_attachments WHERE tenant_id = ? AND work_order_id = ?", Integer.class, m.tenantId(), order);
            if (count >= 20) throw new ResponseStatusException(HttpStatus.CONFLICT, "O limite é de 20 anexos por ordem de serviço.");
            UUID id = UUID.randomUUID();
            String key = "tenants/" + m.tenantId() + "/work-orders/" + order + "/" + id + "." + validated.extension();
            // Register before PUT: a timeout can occur after S3 has already accepted the object.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        try { storage.delete(key); }
                        catch (RuntimeException exception) { log.warn("Attachment rollback cleanup failed for key {}", key); }
                    }
                }
            });
            storage.put(key, validated.content(), validated.contentType(), validated.checksum());
            jdbc.update("""
                    INSERT INTO public.work_order_attachments(id, tenant_id, work_order_id, upload_id, slot, storage_key, original_name,
                        content_type, size_bytes, checksum, source_checksum, uploaded_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, m.tenantId(), order, uploadId, count + 1, key, validated.originalName(), validated.contentType(),
                    validated.content().length, validated.checksum(), validated.sourceChecksum(), m.userId());
            audit.record(m.userId(), "WORK_ORDER_ATTACHMENT_UPLOADED", "WORK_ORDER", order,
                    mapper.writeValueAsString(Map.of("attachmentId", id, "contentType", validated.contentType(), "sizeBytes", validated.content().length)));
            return load(m.tenantId(), order).stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
        });
    }
    @Override public FileStorage.Download download(UUID id) {
        return tenants.execute(tenant(), m -> {
            var rows = jdbc.queryForList("SELECT * FROM public.work_order_attachments WHERE tenant_id = ? AND id = ?", m.tenantId(), id);
            if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Anexo não encontrado.");
            var attachment = rows.getFirst();
            authorize(m, (UUID) attachment.get("work_order_id"), false);
            String name = (String) attachment.get("original_name");
            String key = (String) attachment.get("storage_key");
            // WebP images are sanitized to PNG; the downloaded suffix must match the stored MIME.
            String extension = key.substring(key.lastIndexOf('.'));
            name = (name.lastIndexOf('.') > 0 ? name.substring(0, name.lastIndexOf('.')) : name) + extension;
            var signed = storage.download(key, (String) attachment.get("content_type"), name);
            audit.record(m.userId(), "WORK_ORDER_ATTACHMENT_DOWNLOAD_URL_ISSUED", "WORK_ORDER", (UUID) attachment.get("work_order_id"),
                    mapper.writeValueAsString(Map.of("attachmentId", id)));
            return signed;
        });
    }
}
