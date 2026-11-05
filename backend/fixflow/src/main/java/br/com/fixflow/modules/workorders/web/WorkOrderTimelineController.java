package br.com.fixflow.modules.workorders.web;

import br.com.fixflow.modules.workorders.application.WorkOrderTimeline;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/work-orders/{orderId}/timeline")
public class WorkOrderTimelineController {
    private final WorkOrderTimeline timeline;
    public WorkOrderTimelineController(WorkOrderTimeline timeline) { this.timeline = timeline; }
    @GetMapping public WorkOrderTimeline.Page list(@PathVariable UUID orderId, @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        return timeline.list(orderId, page, size);
    }
}
