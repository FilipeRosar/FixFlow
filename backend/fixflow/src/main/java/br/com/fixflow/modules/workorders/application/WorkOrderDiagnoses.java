package br.com.fixflow.modules.workorders.application;

import br.com.fixflow.modules.workorders.domain.Diagnosis;
import java.util.List;
import java.util.UUID;

public interface WorkOrderDiagnoses {
    record Saved(Diagnosis diagnosis, int workOrderVersion) { }
    Saved record(UUID orderId, int version, String findings, String probableCause, String proposedSolution);
    List<Diagnosis> history(UUID orderId);
}
