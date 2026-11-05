import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription } from 'rxjs';
import { SessionService } from '../../../../core/auth/data-access/session.service';
interface Order {
  id: string;
  number: number;
  customerName: string;
  equipmentLabel: string;
  technicianName: string | null;
  status: string;
  openedAt: string;
}
interface Page {
  content: Order[];
  totalElements: number;
  page: number;
  size: number;
}
@Component({
  selector: 'app-work-order-queue',
  imports: [DatePipe, FormsModule, RouterLink],
  templateUrl: './work-order-queue.html',
  styleUrls: ['../customers/customers.css', './work-order-queue.css'],
})
export class WorkOrderQueue implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly destroy = inject(DestroyRef);
  protected readonly technician = inject(SessionService).session()?.role === 'TECHNICIAN';
  protected readonly data = signal<Page>({ content: [], totalElements: 0, page: 0, size: 20 });
  protected readonly loading = signal(false);
  protected readonly error = signal('');
  protected readonly teamError = signal('');
  protected readonly team = signal<{ userId: string; fullName: string }[]>([]);
  protected technicianId = '';
  protected status = '';
  protected openOnly = true;
  protected readonly statuses = [
    { value: 'RECEBIDA', label: 'Recebida' },
    { value: 'EM_DIAGNOSTICO', label: 'Em diagnóstico' },
    { value: 'AGUARDANDO_ORCAMENTO', label: 'Aguardando orçamento' },
    { value: 'AGUARDANDO_APROVACAO', label: 'Aguardando aprovação' },
    { value: 'APROVADA', label: 'Aprovada' },
    { value: 'ORCAMENTO_REJEITADO', label: 'Orçamento rejeitado' },
    { value: 'EM_MANUTENCAO', label: 'Em manutenção' },
    { value: 'AGUARDANDO_PECA', label: 'Aguardando peça' },
    { value: 'PRONTA', label: 'Pronta' },
    { value: 'SEM_REPARO', label: 'Sem reparo' },
    { value: 'ENTREGUE', label: 'Entregue' },
    { value: 'CANCELADA', label: 'Cancelada' },
  ];
  private pending?: Subscription;
  ngOnInit(): void {
    if (!this.technician) this.loadTeam();
    this.load(0);
  }
  protected statusLabel(status: string): string {
    return this.statuses.find((item) => item.value === status)?.label ?? status;
  }
  protected loadTeam(): void {
    this.teamError.set('');
    this.http
      .get<{ userId: string; fullName: string }[]>('/api/v1/work-orders/technicians')
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (team) => this.team.set(team),
        error: () => this.teamError.set('Não foi possível carregar o filtro de técnicos.'),
      });
  }
  protected filter(): void {
    if (this.status === 'ENTREGUE' || this.status === 'CANCELADA') this.openOnly = false;
    this.load(0);
  }
  protected load(page: number): void {
    this.pending?.unsubscribe();
    this.loading.set(true);
    this.error.set('');
    const params: Record<string, string | number | boolean> = {
      page,
      size: 20,
      openOnly: this.openOnly,
    };
    if (this.status) params['status'] = this.status;
    if (!this.technician && this.technicianId) params['technicianId'] = this.technicianId;
    this.pending = this.http
      .get<Page>('/api/v1/work-orders', { params })
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (data) => {
          this.data.set(data);
          this.loading.set(false);
        },
        error: (error) => {
          this.loading.set(false);
          this.data.set({ content: [], totalElements: 0, page, size: 20 });
          this.error.set(
            error.status === 403
              ? 'Você não tem permissão para consultar esta fila.'
              : 'Não foi possível carregar as ordens de serviço. Tente novamente.',
          );
        },
      });
  }
}
