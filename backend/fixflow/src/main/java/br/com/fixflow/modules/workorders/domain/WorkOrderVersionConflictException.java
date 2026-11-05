package br.com.fixflow.modules.workorders.domain;

public class WorkOrderVersionConflictException extends RuntimeException {
    public WorkOrderVersionConflictException() { super("A OS foi alterada. Atualize os dados antes de tentar novamente."); }
}
