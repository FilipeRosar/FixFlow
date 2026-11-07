import { Component, DestroyRef, LOCALE_ID, OnInit, inject, signal } from '@angular/core';
import { CurrencyPipe, DatePipe, DecimalPipe, registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { Meta } from '@angular/platform-browser';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription, timer } from 'rxjs';

registerLocaleData(localePt, 'pt-BR');
interface PublicQuoteView {
  companyName: string;
  workOrderNumber: number;
  equipmentLabel: string;
  workOrderStatus: string;
  readOnly: boolean;
  contentHash: string;
  decision: { decision: string; decidedAt: string; reason: string | null } | null;
  quote: {
    version: number;
    status: string;
    laborSubtotal: number;
    partsSubtotal: number;
    discountAmount: number;
    total: number;
    validUntil: string;
    warrantyTerms: string;
    items: { type: string; description: string; quantity: number; unitPrice: number }[];
  };
}
@Component({
  selector: 'app-public-quote',
  imports: [CurrencyPipe, DatePipe, DecimalPipe, FormsModule],
  providers: [{ provide: LOCALE_ID, useValue: 'pt-BR' }],
  templateUrl: './public-quote.html',
  styleUrl: './public-quote.css',
})
export class PublicQuote implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly destroy = inject(DestroyRef);
  private readonly meta = inject(Meta);
  private token = '';
  private request?: Subscription;
  private cooldown?: Subscription;
  private decisionRequest?: Subscription;
  private generation = 0;
  private pendingDecision: { fingerprint: string; key: string } | null = null;
  protected readonly choice = signal<'APPROVED' | 'REJECTED' | null>(null);
  protected readonly deciding = signal(false);
  protected readonly decisionError = signal('');
  protected readonly decisionNotice = signal('');
  protected readonly decisionConflict = signal(false);
  protected confirmed = false;
  protected reason = '';
  protected choose(choice: 'APPROVED' | 'REJECTED'): void {
    if (this.deciding() || this.retryAfter() || this.view()?.readOnly || this.decisionConflict())
      return;
    this.choice.set(choice);
    this.confirmed = false;
    this.reason = '';
    this.decisionError.set('');
  }
  protected cancelDecision(): void {
    if (this.deciding()) return;
    this.choice.set(null);
    this.confirmed = false;
    this.decisionError.set('');
  }
  protected decide(): void {
    const view = this.view(),
      choice = this.choice();
    if (
      !view ||
      view.readOnly ||
      !choice ||
      !this.confirmed ||
      this.deciding() ||
      this.retryAfter() ||
      this.decisionConflict() ||
      !/^[0-9a-f]{64}$/.test(view.contentHash ?? '') ||
      this.reason.length > 2000
    )
      return;
    const body = {
      confirmed: true,
      contentHash: view.contentHash,
      reason: choice === 'REJECTED' ? this.reason.trim() || null : null,
    };
    const fingerprint = JSON.stringify({ token: this.token, choice, ...body });
    if (this.pendingDecision?.fingerprint !== fingerprint)
      this.pendingDecision = { fingerprint, key: crypto.randomUUID() };
    const generation = this.generation;
    this.deciding.set(true);
    this.decisionError.set('');
    this.decisionNotice.set('');
    this.decisionRequest = this.http
      .post<{
        decision: string;
        workOrderStatus: string;
        quoteVersion: number;
        decidedAt: string;
        contentHash: string;
      }>(
        `/public/v1/work-orders/${this.token}/${choice === 'APPROVED' ? 'approve' : 'reject'}`,
        body,
        { headers: { 'Idempotency-Key': this.pendingDecision.key } },
      )
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (receipt) => {
          if (generation !== this.generation) return;
          this.deciding.set(false);
          this.pendingDecision = null;
          this.choice.set(null);
          this.confirmed = false;
          this.view.set({
            ...view,
            workOrderStatus: receipt.workOrderStatus,
            readOnly: true,
            contentHash: receipt.contentHash,
            quote: { ...view.quote, status: receipt.decision },
            decision: {
              decision: receipt.decision,
              decidedAt: receipt.decidedAt,
              reason: body.reason,
            },
          });
          this.decisionNotice.set(
            receipt.decision === 'APPROVED'
              ? 'Aprovação registrada. A assistência já pode dar continuidade ao atendimento.'
              : 'Rejeição registrada. A assistência foi informada da sua decisão.',
          );
        },
        error: (error: HttpErrorResponse) => {
          if (generation !== this.generation) return;
          this.deciding.set(false);
          if (error.status === 404) {
            this.view.set(null);
            this.error.set('Orçamento não encontrado ou indisponível.');
            this.choice.set(null);
          } else if (error.status === 409) {
            this.decisionError.set(
              typeof error.error?.detail === 'string'
                ? error.error.detail
                : 'A proposta já foi decidida ou mudou. Atualize a consulta.',
            );
            this.decisionConflict.set(true);
            this.choice.set(null);
            this.confirmed = false;
          } else if (error.status === 429) {
            this.decisionError.set('Muitas solicitações. Aguarde antes de confirmar novamente.');
            this.startCooldown(error);
          } else
            this.decisionError.set(
              error.status > 0 && error.status < 500 && typeof error.error?.detail === 'string'
                ? error.error.detail
                : 'Não foi possível confirmar o resultado. Tente novamente para recuperar a mesma decisão sem duplicá-la.',
            );
        },
      });
  }
  private startCooldown(error: HttpErrorResponse): void {
    const value = Number(error.headers.get('Retry-After'));
    this.retryAfter.set(
      Number.isFinite(value) && value > 0 ? Math.min(3600, Math.ceil(value)) : 60,
    );
    this.cooldown?.unsubscribe();
    this.cooldown = timer(1000, 1000)
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe(() => {
        this.retryAfter.update((seconds) => Math.max(0, seconds - 1));
        if (!this.retryAfter()) this.cooldown?.unsubscribe();
      });
  }
  protected readonly view = signal<PublicQuoteView | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal('');
  protected readonly retryable = signal(false);
  protected readonly retryAfter = signal(0);
  constructor() {
    const previousRobots = this.meta.getTag('name="robots"')?.content;
    const previousReferrer = this.meta.getTag('name="referrer"')?.content;
    this.meta.updateTag({ name: 'robots', content: 'noindex, nofollow, noarchive' });
    this.meta.updateTag({ name: 'referrer', content: 'no-referrer' });
    this.destroy.onDestroy(() => {
      this.token = '';
      for (const [name, content] of [
        ['robots', previousRobots],
        ['referrer', previousReferrer],
      ]) {
        if (content === undefined) this.meta.removeTag(`name="${name}"`);
        else this.meta.updateTag({ name: name!, content });
      }
    });
  }
  ngOnInit(): void {
    this.route.fragment.pipe(takeUntilDestroyed(this.destroy)).subscribe((fragment) => {
      this.request?.unsubscribe();
      this.decisionRequest?.unsubscribe();
      this.deciding.set(false);
      this.cooldown?.unsubscribe();
      this.retryAfter.set(0);
      const params = new URLSearchParams(fragment ?? '');
      this.token = params.getAll('token').length === 1 ? (params.get('token') ?? '') : '';
      this.load();
    });
  }
  protected load(): void {
    if (this.retryAfter() > 0) return;
    if (this.deciding()) return;
    this.generation++;
    this.pendingDecision = null;
    this.choice.set(null);
    this.confirmed = false;
    this.reason = '';
    this.decisionError.set('');
    this.decisionNotice.set('');
    this.decisionConflict.set(false);
    this.request?.unsubscribe();
    this.view.set(null);
    this.error.set('');
    this.retryable.set(false);
    if (!/^[A-Za-z0-9_-]{43}$/.test(this.token)) {
      this.loading.set(false);
      this.error.set('Orçamento não encontrado ou indisponível.');
      return;
    }
    this.loading.set(true);
    this.request = this.http
      .get<PublicQuoteView>(`/public/v1/work-orders/${this.token}`)
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (view) => {
          this.view.set(view);
          this.loading.set(false);
        },
        error: (error: HttpErrorResponse) => {
          this.loading.set(false);
          if (error.status === 404) this.error.set('Orçamento não encontrado ou indisponível.');
          else if (error.status === 429) {
            this.error.set('Muitas consultas. Aguarde um momento para tentar novamente.');
            this.retryable.set(true);
            this.startCooldown(error);
          } else {
            this.error.set('Não foi possível carregar o orçamento. Tente novamente.');
            this.retryable.set(true);
          }
        },
      });
  }
  protected statusLabel(status: string): string {
    return (
      (
        {
          SENT: 'Aguardando aprovação',
          APPROVED: 'Aprovado',
          REJECTED: 'Rejeitado',
          AGUARDANDO_APROVACAO: 'Aguardando aprovação',
          APROVADA: 'Aprovada',
          EM_MANUTENCAO: 'Em manutenção',
          AGUARDANDO_PECA: 'Aguardando peça',
          PRONTA: 'Pronta para retirada',
          ENTREGUE: 'Entregue',
          ORCAMENTO_REJEITADO: 'Orçamento rejeitado',
        } as Record<string, string>
      )[status] ?? 'Em atendimento'
    );
  }
}
