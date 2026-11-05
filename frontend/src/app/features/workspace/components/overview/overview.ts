import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { forkJoin } from 'rxjs';
import { SessionService } from '../../../../core/auth/data-access/session.service';

interface Order {
  id: string;
  number: number;
  customerName: string;
  equipmentLabel: string;
  status: string;
  openedAt: string;
}
interface Page {
  content: Order[];
  totalElements: number;
}

@Component({
  selector: 'app-overview',
  imports: [DatePipe, RouterLink],
  templateUrl: './overview.html',
  styleUrls: ['../../pages/workspace/workspace.css', './overview.css'],
})
export class Overview implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly destroy = inject(DestroyRef);
  protected readonly role = inject(SessionService).session()?.role;
  protected readonly loading = signal(false);
  protected readonly error = signal('');
  protected readonly recent = signal<Order[]>([]);
  protected readonly counts = signal<Record<string, number>>({});
  protected readonly stages = [
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
  ];
  protected readonly active = computed(() =>
    Object.values(this.counts()).reduce((a, b) => a + b, 0),
  );
  protected readonly metrics = computed(() => [
    {
      label: 'Ordens em aberto',
      value: this.active(),
      hint: 'Todas as etapas, exceto entregues e canceladas',
    },
    {
      label: 'Aguardando aprovação',
      value: this.counts()['AGUARDANDO_APROVACAO'] ?? 0,
      hint: 'Orçamentos aguardando retorno do cliente',
    },
    {
      label: 'Prontos para entrega',
      value: this.counts()['PRONTA'] ?? 0,
      hint: 'Equipamentos aguardando retirada',
    },
  ]);
  protected readonly attention = computed(() =>
    this.stages.filter(
      (s) =>
        [
          'AGUARDANDO_APROVACAO',
          'AGUARDANDO_PECA',
          'PRONTA',
          'ORCAMENTO_REJEITADO',
          'SEM_REPARO',
        ].includes(s.value) && this.counts()[s.value] > 0,
    ),
  );

  ngOnInit(): void {
    this.load();
  }
  protected label(status: string): string {
    return (
      this.stages.find((s) => s.value === status)?.label ??
      { ENTREGUE: 'Entregue', CANCELADA: 'Cancelada' }[status] ??
      status
    );
  }
  protected load(): void {
    if (this.loading()) return;
    this.loading.set(true);
    this.error.set('');
    const requests = this.stages.map((stage) =>
      this.http.get<Page>('/api/v1/work-orders', {
        params: { page: 0, size: 1, openOnly: true, status: stage.value },
      }),
    );
    forkJoin({
      stages: forkJoin(requests),
      recent: this.http.get<Page>('/api/v1/work-orders', {
        params: { page: 0, size: 5, openOnly: false },
      }),
    })
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (result) => {
          this.counts.set(
            Object.fromEntries(
              this.stages.map((stage, i) => [stage.value, result.stages[i].totalElements]),
            ),
          );
          this.recent.set(result.recent.content);
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          this.error.set('Não foi possível atualizar a visão geral. Tente novamente.');
        },
      });
  }
}
