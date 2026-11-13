import { Component, DestroyRef, LOCALE_ID, OnInit, computed, inject, signal } from '@angular/core';
import { CurrencyPipe, DatePipe, registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { fromEvent, merge, timer } from 'rxjs';
import {
  SubscriptionAccess,
  SubscriptionAccessState,
} from '../../../../core/auth/data-access/subscription-access.service';
registerLocaleData(localePt, 'pt-BR');
interface Plan {
  code: string;
  name: string;
  monthlyPrice: number;
  maxUsers: number | null;
  maxOrders: number | null;
  available: boolean;
}
interface Subscription {
  access: SubscriptionAccess;
  trialStartedAt: string;
  trialEndsAt: string;
  currentPlan: string;
  paidFrom: string | null;
  paidUntil: string | null;
  cancelAtPeriodEnd: boolean;
  contract: {
    id: string;
    plan: string;
    amount: number;
    cycle: string;
    state: string;
    cancelledAt: string | null;
    checkoutUrl?: string | null;
  } | null;
  activeUsers: number;
  ordersThisMonth: number;
  gatewayAvailable: boolean;
  plans: Plan[];
  invoices: {
    id: string;
    amount: number;
    dueDate: string;
    status: string;
    url: string | null;
    paidAt: string | null;
    appliedAt: string | null;
  }[];
}
@Component({
  selector: 'app-subscription',
  imports: [FormsModule, CurrencyPipe, DatePipe],
  providers: [{ provide: LOCALE_ID, useValue: 'pt-BR' }],
  templateUrl: './subscription.html',
  styleUrls: ['../parts/parts.css', './subscription.css'],
})
export class SubscriptionPanel implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly destroy = inject(DestroyRef);
  private readonly accessState = inject(SubscriptionAccessState);
  protected readonly data = signal<Subscription | null>(null);
  protected readonly loading = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal('');
  protected readonly notice = signal('');
  protected readonly selected = signal<Plan | null>(null);
  protected readonly trialChange = signal(false);
  protected readonly canChangeTrial = computed(() => {
    const data = this.data();
    return (
      data?.access.status === 'TRIALING' &&
      data.paidUntil === null &&
      !data.cancelAtPeriodEnd &&
      (!data.contract || data.contract.state === 'CANCELED')
    );
  });
  protected readonly cancelOpen = signal(false);
  protected readonly currentPlan = computed(() =>
    this.data()?.plans.find((p) => p.code === this.data()?.currentPlan),
  );
  protected readonly nextDueDate = computed(() => {
    const data = this.data();
    if (!data || data.cancelAtPeriodEnd) return null;
    return (
      data.invoices
        .filter((invoice) => ['PENDING', 'OVERDUE'].includes(invoice.status))
        .map((invoice) => invoice.dueDate)
        .sort()[0] ?? null
    );
  });
  private pending: { key: string; plan: string } | null = null;
  protected readonly labels: Record<string, string> = {
    PAYMENT_REQUIRED: 'Aguardando primeiro pagamento',
    TRIALING: 'Período de teste',
    ACTIVE: 'Ativa',
    EXPIRED: 'Teste encerrado',
    PAST_DUE: 'Pagamento pendente',
    CANCELED: 'Cancelada',
    CANCELING: 'Cancelamento em processamento',
    READY: 'Contratação pendente',
    CUSTOMER_CREATING: 'Preparando pagador',
    SUBSCRIPTION_READY: 'Preparando assinatura',
    CREATING: 'Contratação em processamento',
    PENDING: 'Aguardando pagamento',
    OVERDUE: 'Vencida',
    CONFIRMED: 'Pagamento confirmado',
    RECEIVED: 'Pagamento recebido',
    REJECTED: 'Pagamento recusado',
    CHARGED_BACK: 'Pagamento contestado',
    REFUNDED: 'Reembolsada',
    DELETED: 'Cancelada',
  };
  ngOnInit(): void {
    merge(timer(15000, 15000), fromEvent(window, 'focus'))
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe(() => this.syncPendingPayment());
    if (new URLSearchParams(window.location.search).get('assinatura') === 'retorno') {
      this.refresh();
    } else {
      this.load();
    }
  }
  private syncPendingPayment(): void {
    const data = this.data();
    if (
      document.hidden ||
      this.busy() ||
      this.loading() ||
      data?.access.status !== 'PAYMENT_REQUIRED' ||
      !data.gatewayAvailable ||
      data.contract?.state !== 'ACTIVE' ||
      data.cancelAtPeriodEnd
    )
      return;
    this.busy.set(true);
    this.http
      .post<Subscription>('/api/v1/subscription/refresh', {})
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (updated) => {
          this.accept(updated);
          this.busy.set(false);
          if (updated.access.writable) {
            this.error.set('');
            this.notice.set('Pagamento confirmado. Seu acesso foi liberado.');
          }
        },
        error: () => this.busy.set(false),
      });
  }
  protected load(preserveError = false): void {
    if (this.busy() || this.loading()) return;
    this.loading.set(true);
    if (!preserveError) this.error.set('');
    this.http
      .get<Subscription>('/api/v1/subscription')
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (data) => {
          this.accept(data);
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          this.error.set('Não foi possível carregar sua assinatura. Tente novamente.');
        },
      });
  }
  private accept(data: Subscription): void {
    this.data.set(data);
    this.accessState.update(data.access);
    if (
      data.access.status === 'PAYMENT_REQUIRED' &&
      (!data.contract || data.contract.state === 'CANCELED')
    ) {
      this.selected.set(data.plans.find((plan) => plan.code === data.currentPlan) ?? null);
    }
  }
  protected choose(plan: Plan, trial = false): void {
    if (
      this.busy() ||
      this.loading() ||
      !plan.available ||
      (trial && (!this.canChangeTrial() || plan.code !== 'STARTER'))
    )
      return;
    if (this.pending?.plan !== plan.code) this.pending = null;
    this.trialChange.set(trial);
    this.selected.set(plan);
    this.notice.set('');
    this.error.set('');
  }
  protected subscribe(): void {
    const selected = this.selected();
    if (!selected || this.busy()) return;
    if (this.trialChange()) {
      if (!this.canChangeTrial()) return;
      this.operation(
        '/plan',
        { plan: selected.code },
        null,
        'Plano de teste alterado. O prazo do teste permanece o mesmo.',
      );
      return;
    }
    if (!this.data()?.gatewayAvailable || !selected.available) return;
    this.pending ??= { key: crypto.randomUUID(), plan: selected.code };
    this.operation(
      '',
      { plan: this.pending.plan },
      this.pending.key,
      'Contratação registrada. Continue no checkout para autorizar sua assinatura.',
    );
  }
  protected exportData(): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    this.http
      .get('/api/v1/tenant/export', { responseType: 'blob' })
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (data) => {
          this.busy.set(false);
          const url = URL.createObjectURL(data);
          const link = document.createElement('a');
          link.href = url;
          link.download = 'fixflow-dados-csv.zip';
          link.click();
          setTimeout(() => URL.revokeObjectURL(url), 1000);
          this.notice.set('Exportação preparada. Confira o arquivo nos downloads.');
        },
        error: () => {
          this.busy.set(false);
          this.error.set('Não foi possível exportar os dados. Tente novamente.');
        },
      });
  }
  protected declineTrial(): void {
    this.operation(
      '/cancel',
      {},
      null,
      'Teste encerrado sem contratação. Seus dados continuam disponíveis para consulta e exportação.',
    );
  }
  protected refresh(): void {
    this.operation('/refresh', {}, null, 'Assinatura e pagamentos atualizados.');
  }
  protected cancel(): void {
    if (!this.cancelOpen()) return;
    this.operation(
      '/cancel',
      {},
      null,
      'Renovação cancelada. O acesso permanece até o fim do período vigente.',
    );
  }
  private operation(path: string, body: object, key: string | null, message: string): void {
    if (this.busy() || this.loading()) return;
    this.busy.set(true);
    this.error.set('');
    this.notice.set('');
    this.http
      .post<Subscription>(
        `/api/v1/subscription${path}`,
        body,
        key ? { headers: { 'Idempotency-Key': key } } : {},
      )
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (data) => {
          this.accept(data);
          this.busy.set(false);
          this.selected.set(null);
          this.cancelOpen.set(false);
          this.pending = null;
          this.notice.set(message);
        },
        error: (err) => {
          this.busy.set(false);
          this.error.set(
            err.error?.detail ||
              'Não foi possível confirmar a operação. Use “Atualizar pagamentos” para verificar a mesma solicitação.',
          );
          if (err.status >= 400 && err.status < 500) this.pending = null;
          // GET never creates a payment; expose the durable attempt after a partial failure.
          this.load(true);
        },
      });
  }
}
