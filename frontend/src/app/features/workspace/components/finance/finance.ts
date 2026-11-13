import { Component, DestroyRef, LOCALE_ID, OnInit, inject, signal } from '@angular/core';
import { CurrencyPipe, DatePipe, registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
registerLocaleData(localePt, 'pt-BR');
interface Report {
  summary: { received: number; voided: number; confirmedCount: number };
  content: {
    id: string;
    workOrderId: string;
    number: number;
    customerName: string;
    amount: number;
    method: string;
    status: string;
    paidAt: string;
  }[];
  totalElements: number;
}
@Component({
  selector: 'app-finance',
  imports: [FormsModule, CurrencyPipe, DatePipe, RouterLink],
  providers: [{ provide: LOCALE_ID, useValue: 'pt-BR' }],
  templateUrl: './finance.html',
  styleUrls: ['../parts/parts.css', './finance.css'],
})
export class FinancePanel implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly destroy = inject(DestroyRef);
  protected readonly report = signal<Report | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal('');
  protected page = 0;
  private date(value: Date): string {
    return `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, '0')}-${String(value.getDate()).padStart(2, '0')}`;
  }
  protected from = this.date(new Date(new Date().getFullYear(), new Date().getMonth(), 1));
  protected to = this.date(new Date());
  protected status = '';
  protected method = '';
  protected readonly methods: Record<string, string> = {
    PIX: 'Pix',
    CASH: 'Dinheiro',
    CREDIT_CARD: 'Cartão de crédito',
    DEBIT_CARD: 'Cartão de débito',
    BANK_TRANSFER: 'Transferência',
    OTHER: 'Outro',
  };
  protected readonly methodOptions = Object.entries(this.methods);
  ngOnInit(): void {
    this.load();
  }
  protected load(page = 0): void {
    if (this.loading()) return;
    const from = new Date(`${this.from}T00:00:00`);
    const to = new Date(`${this.to}T00:00:00`);
    to.setDate(to.getDate() + 1);
    if (
      !Number.isFinite(from.getTime()) ||
      !Number.isFinite(to.getTime()) ||
      from >= to ||
      to.getTime() - from.getTime() > 367 * 86400000
    ) {
      this.error.set('Informe um período válido de até um ano.');
      this.report.set(null);
      return;
    }
    this.page = page;
    this.loading.set(true);
    this.error.set('');
    this.report.set(null);
    this.http
      .get<Report>('/api/v1/finance', {
        params: {
          from: from.toISOString(),
          to: to.toISOString(),
          status: this.status,
          method: this.method,
          page,
        },
      })
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (report) => {
          this.report.set(report);
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          this.error.set('Não foi possível carregar o financeiro. Tente novamente.');
        },
      });
  }
}
