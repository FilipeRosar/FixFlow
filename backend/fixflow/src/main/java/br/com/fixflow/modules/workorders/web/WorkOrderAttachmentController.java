package br.com.fixflow.modules.workorders.web;

import br.com.fixflow.modules.workorders.application.WorkOrderAttachments;
import br.com.fixflow.modules.workorders.domain.WorkOrderAttachment;
import br.com.fixflow.shared.application.files.FileStorage;
import br.com.fixflow.shared.application.files.AttachmentValidation;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class WorkOrderAttachmentController {
    private final WorkOrderAttachments attachments;
    public WorkOrderAttachmentController(WorkOrderAttachments attachments) { this.attachments = attachments; }
    @GetMapping("/work-orders/{orderId}/attachments")
    public List<WorkOrderAttachment> list(@PathVariable UUID orderId) { return attachments.list(orderId); }
    @PostMapping(value = "/work-orders/{orderId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<WorkOrderAttachment> upload(@PathVariable UUID orderId, @RequestPart("file") MultipartFile file,
                                                      @RequestParam(required = false) UUID uploadId) throws IOException {
        if (file.getSize() > AttachmentValidation.MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "O limite é de 10 MB por arquivo.");
        byte[] content;
        try (var stream = file.getInputStream()) { content = stream.readNBytes(AttachmentValidation.MAX_BYTES + 1); }
        var uploaded = attachments.upload(orderId, uploadId == null ? UUID.randomUUID() : uploadId, file.getOriginalFilename(), file.getContentType(), content);
        return ResponseEntity.created(URI.create("/api/v1/attachments/" + uploaded.id() + "/download-url")).body(uploaded);
    }
    @GetMapping("/attachments/{attachmentId}/download-url")
    public ResponseEntity<FileStorage.Download> download(@PathVariable UUID attachmentId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(attachments.download(attachmentId));
    }
}
