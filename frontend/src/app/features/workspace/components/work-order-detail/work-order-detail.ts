import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
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
}
@Component({
  selector: 'app-work-order-detail',
  imports: [DatePipe, RouterLink],
  templateUrl: './work-order-detail.html',
  styleUrls: ['../customers/customers.css', './work-order-detail.css'],
})
export class WorkOrderDetail implements OnInit {
  private readonly http = inject(HttpClient);
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
          this.id = id;
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
        this.loading.set(false);
      });
  }
}
