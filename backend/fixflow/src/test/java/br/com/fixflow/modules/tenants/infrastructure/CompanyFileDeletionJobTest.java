package br.com.fixflow.modules.tenants.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.mockito.Mockito.*;

class CompanyFileDeletionJobTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final br.com.fixflow.shared.application.files.FileStorage attachments=mock(br.com.fixflow.shared.application.files.FileStorage.class);
    final br.com.fixflow.modules.tenants.application.FileStorage logos=mock(br.com.fixflow.modules.tenants.application.FileStorage.class);
    final UUID id=UUID.randomUUID();
    final UUID tenant=UUID.randomUUID();
    void pending(String kind) {
        when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of("id",id,"tenant_id",tenant,"kind",kind,"storage_key","key")));
    }
    @Test void removesQueueEntryOnlyAfterStorageDeletion() {
        pending("attachment");
        new CompanyFileDeletionJob(jdbc,attachments,logos,true).clean();
        var order=inOrder(attachments,jdbc);
        order.verify(attachments).delete("key");
        order.verify(jdbc).update("DELETE FROM public.company_file_deletions WHERE id=?",id);
    }
    @Test void preservesQueueForRetryWhenStorageFailsOrIsDisabled() {
        pending("attachment");
        new CompanyFileDeletionJob(jdbc,attachments,logos,false).clean();
        verifyNoInteractions(attachments);
        doThrow(new IllegalStateException("offline")).when(attachments).delete("key");
        new CompanyFileDeletionJob(jdbc,attachments,logos,true).clean();
        verify(jdbc,never()).update(anyString(),any(UUID.class));
    }
    @Test void removesAllCompanyLogosIncludingOlderVersions() {
        pending("logo");
        new CompanyFileDeletionJob(jdbc,attachments,logos,true).clean();
        verify(logos).deleteTenantFiles(tenant);
        verify(jdbc).update("DELETE FROM public.company_file_deletions WHERE id=?",id);
    }
}
