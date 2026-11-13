package br.com.fixflow.modules.tenants.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class CompanyFileDeletionJob {
    private final JdbcTemplate jdbc;
    private final boolean attachmentsEnabled;
    private final br.com.fixflow.shared.application.files.FileStorage attachments;
    private final br.com.fixflow.modules.tenants.application.FileStorage logos;
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(CompanyFileDeletionJob.class);
    public CompanyFileDeletionJob(JdbcTemplate jdbc,br.com.fixflow.shared.application.files.FileStorage attachments,
            br.com.fixflow.modules.tenants.application.FileStorage logos,
            @org.springframework.beans.factory.annotation.Value("${fixflow.attachments.enabled:false}") boolean attachmentsEnabled) {
        this.jdbc=jdbc;this.attachments=attachments;this.logos=logos;this.attachmentsEnabled=attachmentsEnabled;
    }
    @Scheduled(fixedDelayString="${fixflow.files.deletion-delay-ms:30000}")
    public void clean() {
        for(var row:jdbc.queryForList("SELECT id,tenant_id,kind,storage_key FROM public.company_file_deletions LIMIT 100")) {
            try {
                String key=(String)row.get("storage_key");
                if("logo".equals(row.get("kind"))) logos.deleteTenantFiles((UUID)row.get("tenant_id"));
                else {
                    if(!attachmentsEnabled) continue;
                    attachments.delete(key);
                }
                jdbc.update("DELETE FROM public.company_file_deletions WHERE id=?",row.get("id"));
            } catch(RuntimeException failure) { log.warn("Company file cleanup will retry entry {}",row.get("id")); }
        }
    }
}
