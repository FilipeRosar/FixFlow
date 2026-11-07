package br.com.fixflow.modules.workorders.domain;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static br.com.fixflow.modules.workorders.domain.WorkOrderStatus.*;

public final class WorkOrderStateMachine {
    public record Transition(String eventType, boolean technical) { }
    private static final Map<WorkOrderStatus, Map<WorkOrderStatus, Transition>> RULES = new EnumMap<>(WorkOrderStatus.class);
    static {
        rule(RECEBIDA, EM_DIAGNOSTICO, "DiagnosisStarted", true);
        rule(EM_DIAGNOSTICO, AGUARDANDO_ORCAMENTO, "DiagnosisCompleted", true);
        rule(EM_DIAGNOSTICO, SEM_REPARO, "WorkOrderUnrepairable", true);
        rule(AGUARDANDO_ORCAMENTO, AGUARDANDO_APROVACAO, "QuoteSent", false);
        rule(AGUARDANDO_APROVACAO, APROVADA, "QuoteApproved", false);
        rule(AGUARDANDO_APROVACAO, ORCAMENTO_REJEITADO, "QuoteRejected", false);
        rule(AGUARDANDO_APROVACAO, AGUARDANDO_ORCAMENTO, "QuoteRevisionRequested", false);
        rule(ORCAMENTO_REJEITADO, AGUARDANDO_ORCAMENTO, null, false);
        rule(APROVADA, EM_MANUTENCAO, "MaintenanceStarted", true);
        rule(EM_MANUTENCAO, AGUARDANDO_PECA, "PartMissing", true);
        rule(AGUARDANDO_PECA, EM_MANUTENCAO, "PartArrived", true);
        rule(EM_MANUTENCAO, PRONTA, "WorkOrderReady", true);
        rule(PRONTA, ENTREGUE, "WorkOrderDelivered", false);
        rule(ORCAMENTO_REJEITADO, ENTREGUE, "WorkOrderDelivered", false);
        rule(SEM_REPARO, ENTREGUE, "WorkOrderDelivered", false);
        for (var from : Set.of(RECEBIDA, EM_DIAGNOSTICO, AGUARDANDO_ORCAMENTO, AGUARDANDO_APROVACAO,
                APROVADA, ORCAMENTO_REJEITADO, SEM_REPARO)) rule(from, CANCELADA, "WorkOrderCancelled", false);
    }
    private static void rule(WorkOrderStatus from, WorkOrderStatus to, String event, boolean technical) {
        RULES.computeIfAbsent(from, ignored -> new EnumMap<>(WorkOrderStatus.class)).put(to, new Transition(event, technical));
    }
    public Transition validate(WorkOrderStatus from, WorkOrderStatus to, String role, UUID actor,
                               WorkOrderFacts facts, String reason, boolean paymentOverride, Instant now) {
        if (!Set.of("OWNER", "ATTENDANT", "TECHNICIAN", "CUSTOMER").contains(role)) throw new WorkOrderTransitionDeniedException();
        var transition = RULES.getOrDefault(from, Map.of()).get(to);
        require(transition != null, "Transição de " + from + " para " + to + " não permitida.");
        boolean customerApproval = role.equals("CUSTOMER") && from == AGUARDANDO_APROVACAO && (to == APROVADA || to == ORCAMENTO_REJEITADO);
        if (!customerApproval && !role.equals("OWNER") && !(transition.technical() ? role.equals("TECHNICIAN") : role.equals("ATTENDANT")))
            throw new WorkOrderTransitionDeniedException();
        if (role.equals("TECHNICIAN") && !actor.equals(facts.technicianId())) throw new WorkOrderTransitionDeniedException();
        if (paymentOverride && (!role.equals("OWNER") || to != ENTREGUE))
            throw new WorkOrderTransitionDeniedException();
        if (paymentOverride) require(hasText(reason), "Registre a justificativa da exceção de pagamento.");
        if (to == CANCELADA) {
            require(hasText(reason), "Informe o motivo do cancelamento.");
            require(!facts.maintenanceStarted() && !facts.partsConsumed(), "Não é permitido cancelar após início da manutenção ou consumo de peças.");
        }
        if (to == EM_DIAGNOSTICO) require(facts.technicianId() != null, "Atribua um técnico antes de iniciar o diagnóstico.");
        if (from == EM_DIAGNOSTICO && to == AGUARDANDO_ORCAMENTO) require(hasText(facts.diagnosis()), "Preencha o diagnóstico.");
        if (to == SEM_REPARO) require(hasText(reason), "Informe a justificativa de ausência de reparo.");
        if (to == AGUARDANDO_APROVACAO) {
            require(facts.quoteId() != null && "DRAFT".equals(facts.quoteStatus()), "Informe um orçamento vigente em rascunho.");
            require(facts.quoteItemCount() >= 1 && facts.quoteTotal() != null && facts.quoteTotal().signum() >= 0, "Orçamento precisa ter itens e total não negativo.");
            require(facts.currentQuote(now), "O orçamento está expirado ou sem validade.");
        }
        if (to == APROVADA || to == ORCAMENTO_REJEITADO) {
            require(facts.currentQuote(now) && "SENT".equals(facts.quoteStatus()), "Orçamento vigente enviado e não expirado obrigatório.");
            if (to == APROVADA && !customerApproval) require(hasText(reason), "Aprovação manual exige justificativa.");
        }
        if (from == APROVADA && to == EM_MANUTENCAO)
            require(facts.quoteId() != null && "APPROVED".equals(facts.quoteStatus()), "Manutenção exige orçamento aprovado.");
        if (to == AGUARDANDO_PECA || from == AGUARDANDO_PECA && to == EM_MANUTENCAO)
            require(hasText(facts.missingPart()), "Informe a peça faltante.");
        if (to == PRONTA) {
            require(facts.serviceCount() > 0, "Registre os serviços executados.");
            require(!facts.partsConsumed() || facts.partsWrittenOff(), "Efetue a baixa das peças consumidas.");
        }
        if (to == ENTREGUE) {
            require(facts.paymentSettled() || paymentOverride && hasText(reason), "Quite o pagamento ou registre a exceção do proprietário.");
        }
        if (to == ENTREGUE && from != PRONTA) require(facts.diagnosticFeeRegistered(), "Registre a taxa de diagnóstico, inclusive quando isenta.");
        return transition;
    }
    private static boolean hasText(String value) { return value != null && !value.isBlank(); }
    private static void require(boolean condition, String message) { if (!condition) throw new InvalidStateTransitionException(message); }
}
