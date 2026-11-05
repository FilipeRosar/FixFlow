package br.com.fixflow.modules.workorders.domain;

public class WorkOrderTransitionDeniedException extends RuntimeException {
    public WorkOrderTransitionDeniedException() { super("Alteração de estado não autorizada."); }
}
