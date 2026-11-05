package br.com.fixflow.modules.workorders.web;

import br.com.fixflow.modules.workorders.application.WorkOrderDiagnoses;
import br.com.fixflow.modules.workorders.domain.Diagnosis;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/work-orders/{orderId}/diagnosis")
public class WorkOrderDiagnosisController {
    private final WorkOrderDiagnoses diagnoses;
    public WorkOrderDiagnosisController(WorkOrderDiagnoses diagnoses) { this.diagnoses = diagnoses; }
    public record Request(@NotNull @Min(0) Integer version, @NotBlank @Size(max = 4000) String findings,
                          @NotBlank @Size(max = 4000) String probableCause, @NotBlank @Size(max = 4000) String proposedSolution) { }
    @GetMapping public List<Diagnosis> history(@PathVariable UUID orderId) { return diagnoses.history(orderId); }
    @PostMapping public ResponseEntity<WorkOrderDiagnoses.Saved> record(@PathVariable UUID orderId, @Valid @RequestBody Request request) {
        var saved = diagnoses.record(orderId, request.version(), request.findings().strip(), request.probableCause().strip(), request.proposedSolution().strip());
        return ResponseEntity.created(URI.create("/api/v1/work-orders/" + orderId + "/diagnosis")).body(saved);
    }
}
