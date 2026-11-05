import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { SessionService } from '../../../../core/auth/data-access/session.service';
import { HttpClient } from '@angular/common/http';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { EMPTY, Subject, catchError, map, merge, switchMap, tap } from 'rxjs';
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
}
@Component({
  selector: 'app-work-order-detail',
  imports: [DatePipe, RouterLink, FormsModule],
  templateUrl: './work-order-detail.html',
  styleUrls: ['../customers/customers.css', './work-order-detail.css'],
})
export class WorkOrderDetail implements OnInit {
  private readonly http = inject(HttpClient);
  protected readonly role = inject(SessionService).session()?.role;
  protected readonly changing = signal(false);
  protected readonly transitionError = signal('');
  protected readonly transitionNotice = signal('');
  protected target = '';
  protected reason = '';
  protected paymentOverride = false;
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
      this.changing()
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
      AGUARDANDO_ORCAMENTO: ['AGUARDANDO_APROVACAO', 'CANCELADA'],
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
    if (!order || !this.target || this.changing() || this.assigning()) return;
    this.changing.set(true);
    this.transitionError.set('');
    this.transitionNotice.set('');
    this.http
      .patch<WorkOrder>(`/api/v1/work-orders/${order.id}/status`, {
        toStatus: this.target,
        version: order.version,
        reason: this.reason.trim() || null,
        paymentOverride:
          this.role === 'OWNER' &&
          order.status === 'PRONTA' &&
          this.target === 'ENTREGUE' &&
          this.paymentOverride,
      })
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (updated) => {
          this.order.set(updated);
          this.changing.set(false);
          this.target = '';
          this.reason = '';
          this.paymentOverride = false;
          this.transitionNotice.set('Estado da OS atualizado.');
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
          this.id = id;
          this.assigning.set(false);
          this.assignmentNotice.set('');
          this.target = '';
          this.reason = '';
          this.paymentOverride = false;
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
        this.loading.set(false);
      });
  }
}
