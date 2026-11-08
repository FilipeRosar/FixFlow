import { SessionService } from '../../../../core/auth/data-access/session.service';
import {
  Component,
  DestroyRef,
  LOCALE_ID,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { CurrencyPipe, DatePipe, registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
registerLocaleData(localePt, 'pt-BR');

interface Payment {
  id: string;
  amount: number;
  method: string;
  status: string;
  paidAt: string;
  notes: string | null;
  voidReason?: string | null;
  voidedAt?: string | null;
  voidedBy?: string | null;
}
@Component({
  selector: 'app-work-order-payments',
  providers: [{ provide: LOCALE_ID, useValue: 'pt-BR' }],
  imports: [FormsModule, CurrencyPipe, DatePipe],
  styleUrls: ['../customers/customers.css', './work-order-payments.css'],
  templateUrl: './work-order-payments.html',
})
export class WorkOrderPaymentsPanel {
  readonly order = input.required<{ id: string; financialStatus?: string }>();
  readonly blocked = input(false);
  readonly registered = output<string>();
  protected readonly role = inject(SessionService).session()?.role;
  protected readonly voidTarget = signal<string | null>(null);
  protected voidReason = '';
  protected pendingVoidReason: string | null = null;
  private readonly http = inject(HttpClient);
  private readonly destroy = inject(DestroyRef);
  protected readonly open = signal(false);
  protected readonly loading = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal('');
  protected readonly notice = signal('');
  protected readonly payments = signal<Payment[]>([]);
  protected readonly confirmedTotal = computed(() =>
    this.payments()
      .filter((payment) => payment.status === 'CONFIRMED')
      .reduce((sum, payment) => sum + payment.amount, 0),
  );
  protected readonly methods: Record<string, string> = {
    PIX: 'Pix',
    CASH: 'Dinheiro',
    CREDIT_CARD: 'Cartão de crédito',
    DEBIT_CARD: 'Cartão de débito',
    BANK_TRANSFER: 'Transferência',
    OTHER: 'Outro',
  };
  protected amount: number | null = null;
  protected method = 'PIX';
  protected notes = '';
  protected pending: {
    key: string;
    body: { amount: number; method: string; notes: string; paidAt: string };
  } | null = null;
  protected beginVoid(payment: Payment): void {
    if (
      this.role !== 'OWNER' ||
      this.busy() ||
      this.pending ||
      this.pendingVoidReason !== null ||
      this.blocked() ||
      payment.status !== 'CONFIRMED'
    )
      return;
    this.voidTarget.set(payment.id);
    this.voidReason = '';
    this.error.set('');
    this.notice.set('');
  }
  protected cancelVoid(): void {
    if (this.busy() || this.pendingVoidReason !== null) return;
    this.voidTarget.set(null);
    this.voidReason = '';
  }
  protected confirmVoid(): void {
    const id = this.voidTarget();
    const reason = this.pendingVoidReason ?? this.voidReason.trim();
    if (
      !id ||
      this.role !== 'OWNER' ||
      this.busy() ||
      this.blocked() ||
      !reason ||
      reason.length > 2000
    )
      return;
    this.pendingVoidReason = reason;
    this.busy.set(true);
    this.error.set('');
    this.notice.set('');
    this.http
      .post<Payment>(`/api/v1/payments/${id}/void`, { reason })
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (payment) => {
          this.payments.update((rows) =>
            rows.map((row) => (row.id === payment.id ? payment : row)),
          );
          this.busy.set(false);
          this.pendingVoidReason = null;
          this.voidTarget.set(null);
          this.voidReason = '';
          this.notice.set('Pagamento estornado. O registro foi mantido no histórico.');
          this.registered.emit(this.order().id);
        },
        error: (err) => {
          this.busy.set(false);
          if (err.status >= 400 && err.status < 500) {
            this.pendingVoidReason = null;
            this.error.set(
              err.error?.detail ||
                'Não foi possível estornar este pagamento. Atualize o histórico.',
            );
          } else
            this.error.set(
              'Não foi possível confirmar o estorno. Tente novamente para verificar a mesma ação.',
            );
        },
      });
  }
  protected statusLabel(): string {
    return (
      ({ UNPAID: 'Não pago', PARTIAL: 'Parcial', PAID: 'Pago' } as Record<string, string>)[
        this.order().financialStatus || 'UNPAID'
      ] || 'Não pago'
    );
  }
  protected toggle(): void {
    this.open.update((value) => !value);
    if (this.open()) this.load();
  }
  protected load(): void {
    if (this.busy() || this.loading()) return;
    this.loading.set(true);
    this.http
      .get<Payment[]>(`/api/v1/work-orders/${this.order().id}/payments`)
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (rows) => {
          this.payments.set(rows);
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          this.error.set('Não foi possível consultar os pagamentos.');
        },
      });
  }
  protected save(): void {
    if (this.busy() || this.voidTarget() || this.blocked() || !this.amount || this.amount <= 0)
      return;
    this.pending ??= {
      key: crypto.randomUUID(),
      body: {
        amount: this.amount,
        method: this.method,
        notes: this.notes.trim(),
        paidAt: new Date().toISOString(),
      },
    };
    this.busy.set(true);
    this.error.set('');
    this.notice.set('');
    const order = this.order().id;
    this.http
      .post<Payment>(`/api/v1/work-orders/${order}/payments`, this.pending.body, {
        headers: { 'Idempotency-Key': this.pending.key },
      })
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: () => {
          this.busy.set(false);
          this.pending = null;
          this.amount = null;
          this.notes = '';
          this.notice.set('Pagamento registrado.');
          this.registered.emit(order);
          this.load();
        },
        error: (err) => {
          this.busy.set(false);
          if (err.status >= 400 && err.status < 500) {
            this.pending = null;
            this.error.set(err.error?.detail || 'Confira os dados e o orçamento aprovado.');
          } else
            this.error.set(
              'Não foi possível confirmar o registro. Tente novamente para consultar o mesmo pagamento.',
            );
        },
      });
  }
}
