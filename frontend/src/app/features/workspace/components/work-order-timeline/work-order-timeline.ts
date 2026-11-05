import { Component, DestroyRef, effect, inject, input, signal, untracked } from '@angular/core';
import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription } from 'rxjs';
interface Entry {
  id: string;
  eventType: string;
  actorId: string | null;
  actorName: string;
  occurredAt: string;
  origin: string;
  fromStatus: string | null;
  toStatus: string | null;
  reason: string | null;
  details: {
    paymentOverride?: boolean;
    warrantyDays?: number;
    warrantyUntil?: string | null;
    technicianName?: string;
    revision?: number;
    description?: string;
    itemType?: string;
    quantity?: number;
    filename?: string;
  };
}
interface Page {
  content: Entry[];
  totalElements: number;
  page: number;
  size: number;
}
@Component({
  selector: 'app-work-order-timeline',
  imports: [DatePipe],
  templateUrl: './work-order-timeline.html',
  styleUrls: ['../customers/customers.css', './work-order-timeline.css'],
})
export class WorkOrderTimelinePanel {
  readonly orderId = input.required<string>();
  readonly version = input(0);
  readonly revision = input(0);
  private readonly http = inject(HttpClient);
  private readonly destroy = inject(DestroyRef);
  private pending?: Subscription;
  protected readonly open = signal(false);
  protected readonly loading = signal(false);
  protected readonly error = signal('');
  protected readonly data = signal<Page>({ content: [], totalElements: 0, page: 0, size: 20 });
  constructor() {
    effect(() => {
      this.orderId();
      this.version();
      this.revision();
      if (this.open()) untracked(() => this.load(0));
    });
  }
  protected load(page = this.data().page): void {
    this.pending?.unsubscribe();
    this.loading.set(true);
    this.error.set('');
    this.pending = this.http
      .get<Page>(`/api/v1/work-orders/${this.orderId()}/timeline`, { params: { page, size: 20 } })
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (data) => {
          this.data.set(data);
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          this.error.set('Não foi possível carregar a timeline. Tente novamente.');
        },
      });
  }
  protected label(type: string): string {
    const labels: Record<string, string> = {
      WORK_ORDER_CREATED: 'Ordem de serviço aberta',
      WORK_ORDER_STATUS_CHANGED: 'Etapa do atendimento alterada',
      WORK_ORDER_TECHNICIAN_ASSIGNED: 'Técnico responsável atribuído',
      WORK_ORDER_DIAGNOSIS_RECORDED: 'Diagnóstico registrado',
      WORK_ORDER_ITEM_ADDED: 'Item adicionado à OS',
      WORK_ORDER_ITEM_REMOVED: 'Item removido da OS',
      WORK_ORDER_ATTACHMENT_UPLOADED: 'Arquivo anexado',
    };
    return labels[type] ?? 'Evento do atendimento';
  }
  protected status(value: string | null): string {
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
    return value ? (labels[value] ?? value) : '';
  }
}
