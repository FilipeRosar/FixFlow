package br.com.fixflow.modules.workorders.application;

import br.com.fixflow.modules.workorders.domain.WorkOrderTimelineEntry;
import java.util.List;
import java.util.UUID;

public interface WorkOrderTimeline {
    record Page(List<WorkOrderTimelineEntry> content, long totalElements, int page, int size) { }
    Page list(UUID order, int page, int size);
}
