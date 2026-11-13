import { WorkOrderPaymentsPanel } from '../work-order-payments/work-order-payments';
import { WorkOrderQuotePanel } from '../work-order-quote/work-order-quote';
import { WorkOrderTimelinePanel } from '../work-order-timeline/work-order-timeline';
import { WorkOrderAttachmentsPanel } from '../work-order-attachments/work-order-attachments';
import { WorkOrderItemsPanel } from '../work-order-items/work-order-items';
import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { SessionService } from '../../../../core/auth/data-access/session.service';
import { HttpClient } from '@angular/common/http';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { EMPTY, Subject, catchError, map, merge, switchMap, tap } from 'rxjs';
interface Diagnosis {
  id: string;
  revision: number;
  findings: string;
  probableCause: string;
  proposedSolution: string;
  recordedBy: string;
  recordedByName: string | null;
  createdAt: string;
}
interface WorkOrder {
  id: string;
  number: number;
  customerId: string;
  equipmentId: string | null;
  customerName: string;
  equipmentLabel: string;
  serialNumber: string | null;
  status: string;
  reportedIssue: string | null;
  receivedAccessories: string[];
  physicalCondition: string | null;
  openedAt: string;
  version: number;
  technicianId: string | null;
  technicianName: string | null;
  diagnosis: Diagnosis | null;
  readyAt: string | null;
  deliveredAt: string | null;
  warrantyDays: number | null;
  warrantyUntil: string | null;
  financialStatus?: string;
  paymentSettled: boolean;
  deliveryPaymentOverride: boolean;
  deliveryReason: string | null;
  deliveredByName: string | null;
  warrantyTerms: string;
}
@Component({
  selector: 'app-work-order-detail',
  imports: [
    DatePipe,
    RouterLink,
    FormsModule,
    WorkOrderPaymentsPanel,
    WorkOrderItemsPanel,
    WorkOrderQuotePanel,
    WorkOrderAttachmentsPanel,
    WorkOrderTimelinePanel,
  ],
  templateUrl: './work-order-detail.html',
  styleUrls: ['../customers/customers.css', './work-order-detail.css'],
})
export class WorkOrderDetail implements OnInit {
  protected readonly downloadingPdf = signal(false);
  protected readonly pdfError = signal('');
  protected downloadPdf(type: 'ENTRY' | 'DELIVERY'): void {
    const order = this.order();
    if (!order || this.downloadingPdf()) return;
    const view = this.viewRequest;
    this.downloadingPdf.set(true);
    this.pdfError.set('');
    this.http
      .get(`/api/v1/work-orders/${order.id}/pdf`, {
        params: { type },
        responseType: 'blob',
      })
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (blob) => {
          if (view !== this.viewRequest) return;
          this.downloadingPdf.set(false);
          const url = URL.createObjectURL(blob);
          const link = document.createElement('a');
          link.href = url;
          link.download = `os-${order.number}-${type === 'ENTRY' ? 'entrada' : 'entrega'}.pdf`;
          link.click();
          setTimeout(() => URL.revokeObjectURL(url), 1000);
        },
        error: () => {
          if (view !== this.viewRequest) return;
          this.downloadingPdf.set(false);
          this.pdfError.set('Não foi possível gerar o PDF. Tente novamente.');
        },
      });
  }
  private readonly http = inject(HttpClient);
  protected readonly role = inject(SessionService).session()?.role;
  protected readonly timelineRevision = signal(0);
  protected readonly quotesSaving = signal(false);
  protected paymentRegistered(id: string): void {
    this.http
      .get<WorkOrder>(`/api/v1/work-orders/${id}`)
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (order) => {
          if (this.order()?.id === id) this.order.set(order);
        },
        error: () => {
          this.transitionError.set('Pagamento registrado. Reabra a OS para atualizar a quitação.');
        },
      });
  }
  protected readonly itemsSaving = signal(false);
  protected quoteSent(receipt: {
    workOrderId: string;
    workOrderVersion: number;
    status: string;
  }): void {
    this.order.update((order) =>
      order && order.id === receipt.workOrderId
        ? { ...order, version: receipt.workOrderVersion, status: receipt.status }
        : order,
    );
    this.timelineRevision.update((value) => value + 1);
    this.target = '';
  }
  protected updateItemsVersion(version: number): void {
    this.order.update((order) =>
      order ? { ...order, version: Math.max(order.version, version) } : null,
    );
  }
  protected readonly changing = signal(false);
  protected readonly transitionError = signal('');
  protected readonly transitionNotice = signal('');
  protected target = '';
  protected reason = '';
  protected approvalChannel = '';
  protected paymentOverride = false;
  protected warrantyDays = 0;
  protected canConfirmDelivery(order: WorkOrder): boolean {
    return (
      !!order.paymentSettled ||
      (this.role === 'OWNER' && this.paymentOverride && !!this.reason.trim())
    );
  }
  protected readonly canAssign = this.role === 'OWNER' || this.role === 'ATTENDANT';
  protected readonly technicians = signal<{ userId: string; fullName: string }[]>([]);
  protected readonly techniciansLoading = signal(false);
  protected readonly assigning = signal(false);
  protected readonly assignmentError = signal('');
  protected readonly assignmentNotice = signal('');
  protected selectedTechnician = '';
  protected techniciansLoaded = false;
  protected loadTechnicians(): void {
    if (!this.canAssign || this.techniciansLoading() || this.techniciansLoaded) return;
    this.techniciansLoading.set(true);
    this.assignmentError.set('');
    this.http
      .get<{ userId: string; fullName: string }[]>('/api/v1/work-orders/technicians')
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (technicians) => {
          this.technicians.set(technicians);
          this.techniciansLoading.set(false);
          this.techniciansLoaded = true;
        },
        error: () => {
          this.techniciansLoading.set(false);
          this.assignmentError.set(
            'Não foi possível carregar os técnicos. Clique na seleção para tentar novamente.',
          );
        },
      });
  }
  protected assignTechnician(): void {
    const order = this.order();
    if (
      !order ||
      !this.canAssign ||
      !this.selectedTechnician ||
      this.assigning() ||
      this.itemsSaving() ||
      this.quotesSaving() ||
      this.changing() ||
      this.diagnosing()
    )
      return;
    this.assigning.set(true);
    this.assignmentError.set('');
    this.assignmentNotice.set('');
    this.http
      .patch<WorkOrder>(`/api/v1/work-orders/${order.id}/technician`, {
        technicianId: this.selectedTechnician,
        version: order.version,
      })
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (updated) => {
          if (this.order()?.id !== order.id) return;
          this.order.set(updated);
          this.selectedTechnician = updated.technicianId ?? '';
          this.assigning.set(false);
          this.assignmentNotice.set('Técnico atribuído à OS.');
          this.transitionError.set('');
        },
        error: (error) => {
          if (this.order()?.id !== order.id) return;
          this.assigning.set(false);
          this.assignmentError.set(
            typeof error.error?.detail === 'string' && error.status < 500
              ? error.error.detail
              : 'Não foi possível atribuir o técnico. Tente novamente.',
          );
          if (error.error?.code === 'WORK_ORDER_VERSION_CONFLICT') this.retry.next();
        },
      });
  }
  protected readonly canDiagnose = this.role === 'OWNER' || this.role === 'TECHNICIAN';
  protected readonly diagnosing = signal(false);
  protected readonly diagnosisError = signal('');
  protected readonly diagnosisNotice = signal('');
  protected readonly diagnosisHistory = signal<Diagnosis[]>([]);
  protected readonly historyShown = signal(false);
  protected readonly historyLoading = signal(false);
  protected findings = '';
  protected probableCause = '';
  protected proposedSolution = '';
  private viewRequest = 0;
  protected saveDiagnosis(): void {
    const order = this.order();
    const view = this.viewRequest;
    if (
      !order ||
      !this.canDiagnose ||
      order.status !== 'EM_DIAGNOSTICO' ||
      this.diagnosing() ||
      this.changing() ||
      this.assigning() ||
      this.itemsSaving() ||
      this.quotesSaving() ||
      !this.findings.trim() ||
      !this.probableCause.trim() ||
      !this.proposedSolution.trim()
    )
      return;
    this.diagnosing.set(true);
    this.diagnosisError.set('');
    this.diagnosisNotice.set('');
    this.http
      .post<{ diagnosis: Diagnosis; workOrderVersion: number }>(
        `/api/v1/work-orders/${order.id}/diagnosis`,
        {
          version: order.version,
          findings: this.findings.trim(),
          probableCause: this.probableCause.trim(),
          proposedSolution: this.proposedSolution.trim(),
        },
      )
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (saved) => {
          if (view !== this.viewRequest) return;
          this.order.set({ ...order, version: saved.workOrderVersion, diagnosis: saved.diagnosis });
          this.diagnosing.set(false);
          this.diagnosisNotice.set(
            'Diagnóstico salvo. Uma nova revisão foi adicionada ao histórico.',
          );
          this.transitionError.set('');
          if (this.historyShown())
            this.diagnosisHistory.update((history) => [saved.diagnosis, ...history]);
        },
        error: (error) => {
          if (view !== this.viewRequest) return;
          this.diagnosing.set(false);
          this.diagnosisError.set(
            typeof error.error?.detail === 'string' && error.status < 500
              ? error.error.detail
              : 'Não foi possível salvar o diagnóstico. Tente novamente.',
          );
          if (error.error?.code === 'WORK_ORDER_VERSION_CONFLICT') this.retry.next();
        },
      });
  }
  protected showDiagnosisHistory(): void {
    if (this.historyShown()) {
      this.historyShown.set(false);
      return;
    }
    const order = this.order();
    const view = this.viewRequest;
    if (!order || this.historyLoading()) return;
    this.historyShown.set(true);
    this.historyLoading.set(true);
    this.diagnosisError.set('');
    this.http
      .get<Diagnosis[]>(`/api/v1/work-orders/${order.id}/diagnosis`)
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (history) => {
          if (view !== this.viewRequest) return;
          this.diagnosisHistory.set(history);
          this.historyLoading.set(false);
        },
        error: () => {
          if (view !== this.viewRequest) return;
          this.historyLoading.set(false);
          this.historyShown.set(false);
          this.diagnosisError.set(
            'Não foi possível carregar o histórico do diagnóstico. Tente novamente.',
          );
        },
      });
  }
  protected targets(status: string): string[] {
    const technical: Record<string, string[]> = {
      RECEBIDA: ['EM_DIAGNOSTICO'],
      EM_DIAGNOSTICO: ['AGUARDANDO_ORCAMENTO', 'SEM_REPARO'],
      APROVADA: ['EM_MANUTENCAO'],
      EM_MANUTENCAO: ['AGUARDANDO_PECA', 'PRONTA'],
      AGUARDANDO_PECA: ['EM_MANUTENCAO'],
    };
    const reception: Record<string, string[]> = {
      RECEBIDA: ['CANCELADA'],
      EM_DIAGNOSTICO: ['CANCELADA'],
      AGUARDANDO_ORCAMENTO: ['CANCELADA'],
      AGUARDANDO_APROVACAO: [
        'APROVADA',
        'ORCAMENTO_REJEITADO',
        'AGUARDANDO_ORCAMENTO',
        'CANCELADA',
      ],
      APROVADA: ['CANCELADA'],
      ORCAMENTO_REJEITADO: ['AGUARDANDO_ORCAMENTO', 'ENTREGUE', 'CANCELADA'],
      SEM_REPARO: ['ENTREGUE', 'CANCELADA'],
      PRONTA: ['ENTREGUE'],
    };
    return [
      ...(this.role === 'OWNER' || this.role === 'TECHNICIAN' ? (technical[status] ?? []) : []),
      ...(this.role === 'OWNER' || this.role === 'ATTENDANT' ? (reception[status] ?? []) : []),
    ];
  }
  protected changeStatus(): void {
    const order = this.order();
    if (
      !order ||
      !this.target ||
      this.changing() ||
      this.assigning() ||
      this.diagnosing() ||
      this.itemsSaving() ||
      this.quotesSaving()
    )
      return;
    const approving = this.target === 'APROVADA';
    if (
      approving &&
      (!this.reason.trim() ||
        !['PHONE', 'COUNTER'].includes(this.approvalChannel) ||
        (this.role !== 'OWNER' && this.role !== 'ATTENDANT'))
    ) {
      this.transitionError.set('Informe o canal e a justificativa da aprovação manual.');
      return;
    }
    const delivering = this.target === 'ENTREGUE';
    const days = order.status === 'PRONTA' ? this.warrantyDays : 0;
    if (delivering && (!Number.isInteger(days) || days < 0 || days > 3650)) {
      this.transitionError.set('Informe um prazo adicional entre 0 e 3650 dias inteiros.');
      return;
    }
    if (delivering && !this.canConfirmDelivery(order)) {
      this.transitionError.set(
        'A entrega exige quitação registrada ou autorização justificada do proprietário.',
      );
      return;
    }
    this.changing.set(true);
    this.transitionError.set('');
    this.transitionNotice.set('');
    const request = approving
      ? this.http.post<WorkOrder>(`/api/v1/work-orders/${order.id}/approve`, {
          version: order.version,
          channel: this.approvalChannel,
          justification: this.reason.trim(),
        })
      : delivering
        ? this.http.post<WorkOrder>(`/api/v1/work-orders/${order.id}/deliver`, {
            version: order.version,
            warrantyDays: days,
            reason: this.reason.trim() || null,
            paymentOverride: this.role === 'OWNER' && !order.paymentSettled && this.paymentOverride,
          })
        : this.http.patch<WorkOrder>(`/api/v1/work-orders/${order.id}/status`, {
            toStatus: this.target,
            version: order.version,
            reason: this.reason.trim() || null,
            paymentOverride: false,
          });
    request.pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: (updated) => {
        this.order.set(updated);
        this.changing.set(false);
        this.target = '';
        this.reason = '';
        this.approvalChannel = '';
        this.paymentOverride = false;
        this.transitionNotice.set(
          approving
            ? 'Aprovação manual registrada com justificativa.'
            : delivering
              ? 'Entrega confirmada. A OS foi encerrada.'
              : 'Estado da OS atualizado.',
        );
      },
      error: (error) => {
        this.changing.set(false);
        this.transitionError.set(
          error.status === 409 && typeof error.error?.detail === 'string'
            ? error.error.detail
            : error.status === 403
              ? 'Você não tem permissão para esta transição.'
              : 'Não foi possível atualizar a OS. Tente novamente.',
        );
        if (error.error?.code === 'WORK_ORDER_VERSION_CONFLICT') this.retry.next();
      },
    });
  }
  private readonly route = inject(ActivatedRoute);
  private readonly destroy = inject(DestroyRef);
  protected readonly order = signal<WorkOrder | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal('');
  protected readonly retry = new Subject<void>();
  private id = '';
  protected statusLabel(status: string): string {
    const labels: Record<string, string> = {
      RECEBIDA: 'Recebida',
      EM_DIAGNOSTICO: 'Em diagnóstico',
      AGUARDANDO_ORCAMENTO: 'Aguardando orçamento',
      AGUARDANDO_APROVACAO: 'Aguardando aprovação',
      APROVADA: 'Aprovada',
      ORCAMENTO_REJEITADO: 'Orçamento rejeitado',
      EM_MANUTENCAO: 'Em manutenção',
      AGUARDANDO_PECA: 'Aguardando peça',
      PRONTA: 'Pronta',
      SEM_REPARO: 'Sem reparo',
      ENTREGUE: 'Entregue',
      CANCELADA: 'Cancelada',
    };
    return labels[status] ?? status;
  }
  ngOnInit(): void {
    merge(
      this.route.paramMap.pipe(map((params) => params.get('orderId') ?? '')),
      this.retry.pipe(map(() => this.id)),
    )
      .pipe(
        tap((id) => {
          if (this.id !== id) {
            this.assignmentError.set('');
            this.transitionError.set('');
            this.transitionNotice.set('');
          }
          this.viewRequest++;
          this.downloadingPdf.set(false);
          this.pdfError.set('');
          this.itemsSaving.set(false);
          this.quotesSaving.set(false);
          this.diagnosing.set(false);
          this.historyLoading.set(false);
          this.historyShown.set(false);
          this.diagnosisHistory.set([]);
          if (this.id !== id) this.diagnosisError.set('');
          this.diagnosisNotice.set('');
          this.id = id;
          this.assigning.set(false);
          this.assignmentNotice.set('');
          this.target = '';
          this.reason = '';
          this.approvalChannel = '';
          this.paymentOverride = false;
          this.warrantyDays = 0;
          this.order.set(null);
          this.loading.set(true);
          this.error.set('');
        }),
        switchMap((id) =>
          this.http.get<WorkOrder>(`/api/v1/work-orders/${id}`).pipe(
            catchError((error) => {
              this.loading.set(false);
              this.error.set(
                error.status === 404
                  ? 'Ordem de serviço não encontrada ou indisponível nesta empresa.'
                  : error.status === 403
                    ? 'Você não tem permissão para consultar esta ordem de serviço.'
                    : 'Não foi possível carregar a OS. Tente novamente.',
              );
              return EMPTY;
            }),
          ),
        ),
        takeUntilDestroyed(this.destroy),
      )
      .subscribe((order) => {
        this.order.set(order);
        this.selectedTechnician = order.technicianId ?? '';
        this.findings = order.diagnosis?.findings ?? '';
        this.probableCause = order.diagnosis?.probableCause ?? '';
        this.proposedSolution = order.diagnosis?.proposedSolution ?? '';
        this.loading.set(false);
      });
  }
}
