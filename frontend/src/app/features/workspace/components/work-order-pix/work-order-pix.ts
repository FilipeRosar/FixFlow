import { Component, DestroyRef, LOCALE_ID, inject, input, output, signal } from '@angular/core';
import { CurrencyPipe, DatePipe, registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
registerLocaleData(localePt, 'pt-BR');
interface Charge {
  id: string;
  amount: number;
  dueDate: string;
  status: string;
  payload: string | null;
  encodedImage: string | null;
  expirationDate: string | null;
}
interface Request {
  amount: number;
  asaasCustomerId: string;
  dueDate: string;
}
@Component({
  selector: 'app-work-order-pix',
  imports: [FormsModule, CurrencyPipe, DatePipe],
  providers: [{ provide: LOCALE_ID, useValue: 'pt-BR' }],
  templateUrl: './work-order-pix.html',
  styleUrls: ['../customers/customers.css', './work-order-pix.css'],
})
export class WorkOrderPixPanel {
  readonly orderId = input.required<string>();
  readonly blocked = input(false);
  readonly changed = output<void>();
  private readonly http = inject(HttpClient);
  private readonly destroy = inject(DestroyRef);
  protected readonly open = signal(false);
  protected readonly loading = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal('');
  protected readonly notice = signal('');
  protected readonly charges = signal<Charge[]>([]);
  protected amount: number | null = null;
  protected customer = '';
  protected dueDate = new Date().toLocaleDateString('en-CA', { timeZone: 'America/Sao_Paulo' });
  protected pending: { key: string; body: Request } | null = null;
  protected toggle(): void {
    this.open.update((value) => !value);
    if (this.open()) this.load();
  }
  protected load(): void {
    if (this.busy() || this.loading()) return;
    this.loading.set(true);
    this.error.set('');
    this.http
      .get<Charge[]>(`/api/v1/work-orders/${this.orderId()}/pix-charges`)
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (rows) => {
          this.charges.set(rows);
          this.loading.set(false);
          this.changed.emit();
        },
        error: () => {
          this.loading.set(false);
          this.error.set('Não foi possível consultar as cobranças PIX.');
        },
      });
  }
  protected create(): void {
    if (
      this.busy() ||
      this.blocked() ||
      !this.amount ||
      this.amount <= 0 ||
      !this.customer ||
      !this.dueDate
    )
      return;
    this.pending ??= {
      key: crypto.randomUUID(),
      body: { amount: this.amount, asaasCustomerId: this.customer.trim(), dueDate: this.dueDate },
    };
    this.busy.set(true);
    this.error.set('');
    this.notice.set('');
    this.http
      .post<Charge>(`/api/v1/work-orders/${this.orderId()}/pix-charges`, this.pending.body, {
        headers: { 'Idempotency-Key': this.pending.key },
      })
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (charge) => {
          this.pending = null;
          this.amount = null;
          this.accept(charge);
        },
        error: (err) => {
          this.busy.set(false);
          if (err.status >= 400 && err.status < 500) this.pending = null;
          this.error.set(
            err.error?.detail ||
              'Não foi possível confirmar a cobrança. Consulte novamente usando o mesmo pedido.',
          );
        },
      });
  }
  protected refresh(charge: Charge): void {
    if (this.busy() || this.blocked() || this.pending) return;
    this.busy.set(true);
    this.error.set('');
    this.notice.set('');
    this.http
      .post<Charge>(`/api/v1/work-orders/${this.orderId()}/pix-charges/${charge.id}/refresh`, {})
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (result) => this.accept(result),
        error: (err) => {
          this.busy.set(false);
          this.error.set(err.error?.detail || 'Não foi possível consultar a cobrança no Asaas.');
        },
      });
  }
  private accept(charge: Charge): void {
    this.charges.update((rows) => [charge, ...rows.filter((row) => row.id !== charge.id)]);
    this.busy.set(false);
    this.notice.set(
      charge.status === 'RECEIVED'
        ? 'Recebimento confirmado.'
        : 'Cobrança PIX disponível. A quitação será atualizada após o recebimento.',
    );
    this.changed.emit();
  }
}
