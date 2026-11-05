import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
interface HistoryEntry {
  id: string;
  type: 'WORK_ORDER' | 'EQUIPMENT';
  createdAt: string;
  title: string;
  status: string | null;
  serialNumber: string | null;
  deleted: boolean;
}
interface HistoryPage {
  customerId: string;
  customerName: string;
  content: HistoryEntry[];
  totalElements: number;
  page: number;
  size: number;
  sort: string;
  workOrdersVisible: boolean;
}
@Component({
  selector: 'app-customer-history',
  imports: [DatePipe, FormsModule, RouterLink],
  templateUrl: './customer-history.html',
  styleUrls: ['../customers/customers.css', './customer-history.css'],
})
export class CustomerHistoryPanel implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly destroy = inject(DestroyRef);
  protected readonly data = signal<HistoryPage | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal('');
  protected type = 'ALL';
  protected sort = 'desc';
  protected size = 20;
  protected customerId = '';
  private request = 0;
  private readonly statuses: Record<string, string> = {
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
  protected statusLabel(status: string | null): string {
    return status ? (this.statuses[status] ?? status) : '';
  }
  ngOnInit(): void {
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroy)).subscribe((params) => {
      this.customerId = params.get('customerId') ?? '';
      this.type = 'ALL';
      this.data.set(null);
      this.load(0);
    });
  }
  protected load(page: number): void {
    const request = ++this.request;
    this.loading.set(true);
    this.error.set('');
    this.http
      .get<HistoryPage>(`/api/v1/customers/${this.customerId}/history`, {
        params: { type: this.type, sort: this.sort, size: this.size, page },
      })
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (data) => {
          if (request === this.request) {
            this.data.set(data);
            this.loading.set(false);
          }
        },
        error: (error: HttpErrorResponse) => {
          if (request !== this.request) return;
          this.loading.set(false);
          this.error.set(
            error.status === 404
              ? 'Cliente não encontrado ou indisponível nesta empresa.'
              : error.status === 403
                ? 'Você não tem permissão para consultar este histórico.'
                : 'Não foi possível carregar o histórico. Tente novamente.',
          );
        },
      });
  }
}
