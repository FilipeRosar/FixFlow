package br.com.fixflow.modules.workorders.application;

import br.com.fixflow.modules.workorders.domain.WorkOrderAttachment;
import br.com.fixflow.shared.application.files.FileStorage;
import java.util.List;
import java.util.UUID;

public interface WorkOrderAttachments {
    List<WorkOrderAttachment> list(UUID order);
    WorkOrderAttachment upload(UUID order, UUID uploadId, String filename, String contentType, byte[] content);
    FileStorage.Download download(UUID attachment);
}
