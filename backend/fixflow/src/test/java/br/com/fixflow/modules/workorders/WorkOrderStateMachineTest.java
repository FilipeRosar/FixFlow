package br.com.fixflow.modules.workorders;

import br.com.fixflow.modules.workorders.domain.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static br.com.fixflow.modules.workorders.domain.WorkOrderStatus.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkOrderStateMachineTest {
    final WorkOrderStateMachine machine = new WorkOrderStateMachine();
    final UUID actor = UUID.randomUUID();
    final Instant now = Instant.parse("2026-11-05T12:00:00Z");
    final Set<String> edges = Set.of(
            "RECEBIDA/EM_DIAGNOSTICO", "EM_DIAGNOSTICO/AGUARDANDO_ORCAMENTO", "EM_DIAGNOSTICO/SEM_REPARO",
            "AGUARDANDO_ORCAMENTO/AGUARDANDO_APROVACAO", "AGUARDANDO_APROVACAO/APROVADA",
            "AGUARDANDO_APROVACAO/ORCAMENTO_REJEITADO", "AGUARDANDO_APROVACAO/AGUARDANDO_ORCAMENTO",
            "ORCAMENTO_REJEITADO/AGUARDANDO_ORCAMENTO", "APROVADA/EM_MANUTENCAO",
            "EM_MANUTENCAO/AGUARDANDO_PECA", "AGUARDANDO_PECA/EM_MANUTENCAO", "EM_MANUTENCAO/PRONTA",
            "PRONTA/ENTREGUE", "ORCAMENTO_REJEITADO/ENTREGUE", "SEM_REPARO/ENTREGUE",
            "RECEBIDA/CANCELADA", "EM_DIAGNOSTICO/CANCELADA", "AGUARDANDO_ORCAMENTO/CANCELADA",
            "AGUARDANDO_APROVACAO/CANCELADA", "APROVADA/CANCELADA", "ORCAMENTO_REJEITADO/CANCELADA", "SEM_REPARO/CANCELADA");
    class Evidence {
        UUID technician = actor, quote = UUID.randomUUID();
        String diagnosis = "Fonte danificada", status = "SENT", missingPart = "Fonte";
        int items = 1, services = 1;
        BigDecimal total = BigDecimal.TEN;
        Instant validUntil = now.plusSeconds(3600);
        boolean consumed, writtenOff, paid = true, fee = true, started;
        Evidence(WorkOrderStatus from) { if (from == AGUARDANDO_ORCAMENTO) status = "DRAFT"; if (from == APROVADA) status = "APPROVED"; }
        WorkOrderFacts facts() { return new WorkOrderFacts(technician, diagnosis, quote, status, items, total, validUntil,
                services, consumed, writtenOff, paid, fee, missingPart, started, 90); }
    }
    @Test void coversEveryPairInSection14IncludingAllTerminalAndSelfTransitions() {
        for (var from : WorkOrderStatus.values()) for (var to : WorkOrderStatus.values()) {
            var facts = new Evidence(from).facts();
            if (edges.contains(from + "/" + to)) assertDoesNotThrow(() -> machine.validate(from, to, "OWNER", actor, facts, "Motivo", false, now), from + "/" + to);
            else assertThrows(InvalidStateTransitionException.class, () -> machine.validate(from, to, "OWNER", actor, facts, "Motivo", false, now), from + "/" + to);
        }
    }
    @Test void enforcesPermissionsAndAssignedTechnicianForEveryEdge() {
        for (String edge : edges) {
            String[] parts = edge.split("/"); var from = WorkOrderStatus.valueOf(parts[0]); var to = WorkOrderStatus.valueOf(parts[1]);
            var facts = new Evidence(from).facts();
            var transition = machine.validate(from, to, "OWNER", actor, facts, "Motivo", false, now);
            String allowed = transition.technical() ? "TECHNICIAN" : "ATTENDANT";
            String denied = transition.technical() ? "ATTENDANT" : "TECHNICIAN";
            assertDoesNotThrow(() -> machine.validate(from, to, allowed, actor, facts, "Motivo", false, now));
            assertThrows(WorkOrderTransitionDeniedException.class, () -> machine.validate(from, to, denied, actor, facts, "Motivo", false, now));
            if (transition.technical()) assertThrows(WorkOrderTransitionDeniedException.class, () -> machine.validate(from, to, allowed, UUID.randomUUID(), facts, "Motivo", false, now));
        }
    }
    void fails(WorkOrderStatus from, WorkOrderStatus to, java.util.function.Consumer<Evidence> change) {
        var evidence = new Evidence(from); change.accept(evidence);
        assertThrows(InvalidStateTransitionException.class, () -> machine.validate(from, to, "OWNER", actor, evidence.facts(), "Motivo", false, now));
    }
    @Test void requiresPersistedEvidenceForEachBusinessGuard() {
        fails(RECEBIDA, EM_DIAGNOSTICO, e -> e.technician = null);
        fails(EM_DIAGNOSTICO, AGUARDANDO_ORCAMENTO, e -> e.diagnosis = " ");
        fails(AGUARDANDO_ORCAMENTO, AGUARDANDO_APROVACAO, e -> e.items = 0);
        fails(AGUARDANDO_ORCAMENTO, AGUARDANDO_APROVACAO, e -> e.total = BigDecimal.valueOf(-1));
        fails(AGUARDANDO_ORCAMENTO, AGUARDANDO_APROVACAO, e -> e.quote = null);
        fails(AGUARDANDO_APROVACAO, APROVADA, e -> e.validUntil = now);
        fails(AGUARDANDO_APROVACAO, ORCAMENTO_REJEITADO, e -> e.status = "DRAFT");
        fails(APROVADA, EM_MANUTENCAO, e -> e.status = "SENT");
        fails(EM_MANUTENCAO, AGUARDANDO_PECA, e -> e.missingPart = "");
        fails(AGUARDANDO_PECA, EM_MANUTENCAO, e -> e.missingPart = null);
        fails(EM_MANUTENCAO, PRONTA, e -> e.services = 0);
        fails(EM_MANUTENCAO, PRONTA, e -> { e.consumed = true; e.writtenOff = false; });
        fails(PRONTA, ENTREGUE, e -> e.paid = false);
        fails(SEM_REPARO, ENTREGUE, e -> e.fee = false);
        fails(RECEBIDA, CANCELADA, e -> e.started = true);
        fails(RECEBIDA, CANCELADA, e -> e.consumed = true);
    }
    @Test void requiresReasonsAndRestrictsPaymentExceptionToOwnerDelivery() {
        for (var edge : List.of(new WorkOrderStatus[]{RECEBIDA, CANCELADA}, new WorkOrderStatus[]{EM_DIAGNOSTICO, SEM_REPARO}, new WorkOrderStatus[]{AGUARDANDO_APROVACAO, APROVADA})) {
            assertThrows(InvalidStateTransitionException.class, () -> machine.validate(edge[0], edge[1], "OWNER", actor, new Evidence(edge[0]).facts(), " ", false, now));
        }
        var evidence = new Evidence(PRONTA); evidence.paid = false;
        assertDoesNotThrow(() -> machine.validate(PRONTA, ENTREGUE, "OWNER", actor, evidence.facts(), "Entrega autorizada", true, now));
        assertThrows(WorkOrderTransitionDeniedException.class, () -> machine.validate(PRONTA, ENTREGUE, "ATTENDANT", actor, evidence.facts(), "Entrega autorizada", true, now));
        assertThrows(InvalidStateTransitionException.class, () -> machine.validate(PRONTA, ENTREGUE, "OWNER", actor, evidence.facts(), "", true, now));
    }
    @Test void publicApprovalContextIsLimitedToQuoteDecisionsAndDoesNotRequireManualReason() {
        assertDoesNotThrow(() -> machine.validate(AGUARDANDO_APROVACAO, APROVADA, "CUSTOMER", actor, new Evidence(AGUARDANDO_APROVACAO).facts(), null, false, now));
        assertThrows(WorkOrderTransitionDeniedException.class, () -> machine.validate(RECEBIDA, CANCELADA, "CUSTOMER", actor, new Evidence(RECEBIDA).facts(), "Motivo", false, now));
    }
}
