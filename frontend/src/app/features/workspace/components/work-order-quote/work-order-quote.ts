import {
  Component,
  DestroyRef,
  LOCALE_ID,
  OnChanges,
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
import { SessionService } from '../../../../core/auth/data-access/session.service';

registerLocaleData(localePt, 'pt-BR');
interface Item {
  type: string;
  partId: string | null;
  description: string;
  quantity: number;
  unitPrice: number;
}
interface Quote {
  id: string;
  version: number;
  status: string;
  items: Item[];
  discountType: string;
  discountValue: number;
  laborSubtotal: number;
  partsSubtotal: number;
  discountAmount: number;
  total: number;
  validUntil: string;
  warrantyTerms: string;
  sentAt: string | null;
  linkStatus?: 'NONE' | 'ACTIVE' | 'REVOKED' | 'EXPIRED';
}
interface SendReceipt {
  workOrderId: string;
  quoteId: string;
  workOrderVersion: number;
  status: string;
  notificationStatus: string;
  validUntil: string;
}
interface LinkReceipt extends SendReceipt {
  linkStatus: 'ACTIVE' | 'REVOKED';
}
interface History {
  quotes: Quote[];
  workOrderVersion: number;
}
interface Part {
  id: string;
  name: string;
  salePrice: number;
}
@Component({
  selector: 'app-work-order-quote',
  imports: [FormsModule, CurrencyPipe, DatePipe],
  providers: [{ provide: LOCALE_ID, useValue: 'pt-BR' }],
  templateUrl: './work-order-quote.html',
  styleUrls: ['../customers/customers.css', './work-order-quote.css'],
})
export class WorkOrderQuotePanel implements OnChanges {
  readonly order = input.required<{ id: string; version: number; status: string }>();
  readonly blocked = input(false);
  readonly versionChanged = output<number>();
  readonly busyChanged = output<boolean>();
  readonly saved = output<void>();
  readonly sent = output<SendReceipt>();
  readonly revised = output<{ workOrderId: string; workOrderVersion: number; status: string }>();
  private readonly http = inject(HttpClient);
  private readonly destroy = inject(DestroyRef);
  private readonly role = inject(SessionService).session()?.role;
  protected readonly open = signal(false);
  protected readonly loading = signal(false);
  protected readonly busy = signal(false);
  protected readonly loaded = signal(false);
  protected readonly error = signal('');
  protected readonly notice = signal('');
  protected readonly quotes = signal<Quote[]>([]);
  protected readonly selected = signal<Quote | null>(null);
  protected readonly parts = signal<Part[]>([]);
  protected readonly partsError = signal('');
  protected readonly revisionOpen = signal(false);
  private startRevisionOnLoad = false;
  protected readonly canRevise = computed(
    () =>
      ['OWNER', 'ATTENDANT'].includes(this.role ?? '') &&
      ['AGUARDANDO_APROVACAO', 'ORCAMENTO_REJEITADO'].includes(this.order().status) &&
      !!this.selected() &&
      this.selected()?.id === this.quotes()[0]?.id &&
      ['SENT', 'REJECTED'].includes(this.selected()?.status ?? ''),
  );
  protected revise(): void {
    if (
      !this.revisionOpen() ||
      !this.canRevise() ||
      this.busy() ||
      this.loading() ||
      this.blocked()
    )
      return;
    const order = this.order();
    const request = ++this.request;
    this.busy.set(true);
    this.busyChanged.emit(true);
    this.error.set('');
    this.notice.set('');
    this.http
      .patch<{ id: string; version: number; status: string }>(
        `/api/v1/work-orders/${order.id}/status`,
        {
          toStatus: 'AGUARDANDO_ORCAMENTO',
          version: order.version,
          reason: 'Revisão do orçamento para edição.',
          paymentOverride: false,
        },
      )
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (updated) => {
          if (request !== this.request) return;
          this.busy.set(false);
          this.busyChanged.emit(false);
          this.revisionOpen.set(false);
          this.pendingSend = null;
          this.pendingLink = null;
          this.startRevisionOnLoad = true;
          this.revised.emit({
            workOrderId: order.id,
            workOrderVersion: updated.version,
            status: updated.status,
          });
        },
        error: (error) => {
          if (request !== this.request) return;
          this.busy.set(false);
          this.busyChanged.emit(false);
          this.error.set(
            typeof error.error?.detail === 'string'
              ? error.error.detail
              : 'Não foi possível iniciar a edição. Recarregue o orçamento e tente novamente.',
          );
        },
      });
  }
  protected readonly canEdit = computed(
    () =>
      ['OWNER', 'ATTENDANT', 'TECHNICIAN'].includes(this.role ?? '') &&
      this.order().status === 'AGUARDANDO_ORCAMENTO',
  );
  protected readonly editing = computed(
    () => this.canEdit() && (!this.selected() || this.selected()?.status === 'DRAFT'),
  );
  protected lines: Item[] = [];
  protected discountType = 'FIXED';
  protected discountValue = 0;
  protected readonly sending = signal(false);
  protected readonly canSend = computed(
    () =>
      (this.role === 'OWNER' || this.role === 'ATTENDANT') &&
      this.order().status === 'AGUARDANDO_ORCAMENTO' &&
      this.selected()?.status === 'DRAFT',
  );
  private pendingSend: { quoteId: string; version: number; key: string } | null = null;
  protected hasUnsavedChanges(): boolean {
    const quote = this.selected();
    return (
      !!quote &&
      (JSON.stringify(this.lines) !== JSON.stringify(quote.items) ||
        this.discountType !== quote.discountType ||
        this.discountValue !== quote.discountValue)
    );
  }
  protected sendQuote(): void {
    const quote = this.selected();
    if (!quote || !this.canSend() || this.busy() || this.blocked() || this.hasUnsavedChanges())
      return;
    const order = this.order();
    if (
      !this.pendingSend ||
      this.pendingSend.quoteId !== quote.id ||
      this.pendingSend.version !== order.version
    )
      this.pendingSend = { quoteId: quote.id, version: order.version, key: crypto.randomUUID() };
    const pending = this.pendingSend;
    const request = ++this.request;
    this.busy.set(true);
    this.sending.set(true);
    this.busyChanged.emit(true);
    this.error.set('');
    this.notice.set('');
    this.http
      .post<SendReceipt>(
        `/api/v1/work-orders/${order.id}/quotes/${quote.id}/send`,
        { workOrderVersion: pending.version },
        { headers: { 'Idempotency-Key': pending.key } },
      )
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (receipt) => {
          if (request !== this.request) return;
          this.pendingSend = null;
          const updated: Quote = {
            ...quote,
            status: 'SENT',
            validUntil: receipt.validUntil,
            linkStatus: 'ACTIVE',
          };
          this.quotes.update((quotes) =>
            quotes.map((item) => (item.id === quote.id ? updated : item)),
          );
          this.selected.set(updated);
          this.busy.set(false);
          this.sending.set(false);
          this.busyChanged.emit(false);
          this.sent.emit(receipt);
          this.notice.set('Orçamento enviado para aprovação. O e-mail está na fila de entrega.');
        },
        error: (error) => {
          if (request !== this.request) return;
          this.busy.set(false);
          this.sending.set(false);
          this.busyChanged.emit(false);
          this.error.set(
            error.status < 500 && typeof error.error?.detail === 'string'
              ? error.error.detail
              : 'Não foi possível confirmar o envio. Tente novamente; a mesma solicitação será recuperada sem duplicar o envio.',
          );
          if (error.status === 409)
            this.notice.set('Recarregue o orçamento para conferir a versão atual.');
        },
      });
  }
  protected readonly linkAction = signal<'revoke-link' | 'resend-link' | null>(null);
  protected readonly managingLink = signal(false);
  protected readonly canManageLink = computed(() => {
    const quote = this.selected(),
      status = this.order().status;
    return (
      (this.role === 'OWNER' || this.role === 'ATTENDANT') &&
      !!quote &&
      quote.id === this.quotes()[0]?.id &&
      (quote.status === 'SENT'
        ? status === 'AGUARDANDO_APROVACAO'
        : ['APPROVED', 'REJECTED'].includes(quote.status) &&
          [
            'APROVADA',
            'ORCAMENTO_REJEITADO',
            'EM_MANUTENCAO',
            'AGUARDANDO_PECA',
            'PRONTA',
            'ENTREGUE',
          ].includes(status))
    );
  });
  private pendingLink: { fingerprint: string; key: string } | null = null;
  protected linkLabel(status: string | undefined): string {
    return (
      (
        { ACTIVE: 'Ativo', REVOKED: 'Revogado', EXPIRED: 'Expirado', NONE: 'Sem link' } as Record<
          string,
          string
        >
      )[status ?? 'NONE'] ?? 'Sem link'
    );
  }
  protected chooseLinkAction(action: 'revoke-link' | 'resend-link' | null): void {
    if (this.busy() || this.blocked()) return;
    this.linkAction.set(action);
    this.error.set('');
    this.notice.set('');
  }
  protected manageLink(): void {
    const quote = this.selected(),
      action = this.linkAction(),
      order = this.order();
    if (!quote || !action || !this.canManageLink() || this.busy() || this.blocked()) return;
    const fingerprint = `${order.id}:${quote.id}:${action}:${order.version}`;
    if (this.pendingLink?.fingerprint !== fingerprint)
      this.pendingLink = { fingerprint, key: crypto.randomUUID() };
    const request = ++this.request;
    this.busy.set(true);
    this.managingLink.set(true);
    this.busyChanged.emit(true);
    this.error.set('');
    this.notice.set('');
    this.http
      .post<LinkReceipt>(
        `/api/v1/work-orders/${order.id}/quotes/${quote.id}/${action}`,
        { workOrderVersion: order.version },
        { headers: { 'Idempotency-Key': this.pendingLink.key } },
      )
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (receipt) => {
          if (request !== this.request) return;
          this.pendingLink = null;
          this.linkAction.set(null);
          const updated = { ...quote, linkStatus: receipt.linkStatus };
          this.quotes.update((quotes) =>
            quotes.map((item) => (item.id === quote.id ? updated : item)),
          );
          this.selected.set(updated);
          this.busy.set(false);
          this.managingLink.set(false);
          this.busyChanged.emit(false);
          this.versionChanged.emit(receipt.workOrderVersion);
          this.saved.emit();
          this.notice.set(
            action === 'resend-link'
              ? 'Novo link enviado para a fila de e-mail. O link anterior foi invalidado.'
              : 'Link revogado. O endereço anterior não permite mais acessar a proposta.',
          );
        },
        error: (error) => {
          if (request !== this.request) return;
          this.busy.set(false);
          this.managingLink.set(false);
          this.busyChanged.emit(false);
          this.error.set(
            error.status < 500 && typeof error.error?.detail === 'string'
              ? error.error.detail
              : 'Não foi possível confirmar a operação. Tente novamente para recuperar a mesma solicitação sem duplicá-la.',
          );
          if (error.status === 409)
            this.notice.set('Recarregue o orçamento para conferir seu estado e versão atuais.');
        },
      });
  }
  private lastId = '';
  private lastStatus = '';
  private request = 0;
  ngOnChanges(): void {
    const order = this.order();
    if (order.id !== this.lastId) {
      this.revisionOpen.set(false);
      this.startRevisionOnLoad = false;
      this.request++;
      this.pendingSend = null;
      this.pendingLink = null;
      this.linkAction.set(null);
      this.managingLink.set(false);
      this.sending.set(false);
      this.lastId = order.id;
      this.lastStatus = order.status;
      this.open.set(false);
      this.loaded.set(false);
      this.loading.set(false);
      this.busy.set(false);
      this.quotes.set([]);
      this.selected.set(null);
      this.parts.set([]);
      this.lines = [];
      this.error.set('');
      this.notice.set('');
      this.partsError.set('');
    } else if (order.status !== this.lastStatus) {
      this.lastStatus = order.status;
      this.loaded.set(false);
      if (this.open()) this.load();
    }
  }
  protected toggle(): void {
    this.open.update((value) => !value);
    if (this.open() && !this.loaded()) this.load();
  }
  protected load(): void {
    if (this.busy() || this.blocked()) return;
    const request = ++this.request;
    this.loading.set(true);
    this.error.set('');
    this.http
      .get<History>(`/api/v1/work-orders/${this.order().id}/quotes`)
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (data) => {
          if (request !== this.request) return;
          this.apply(data);
          this.loading.set(false);
          this.loaded.set(true);
          if (this.canEdit()) this.loadParts();
        },
        error: () => {
          if (request !== this.request) return;
          this.loading.set(false);
          this.error.set('Não foi possível carregar o orçamento. Tente novamente.');
        },
      });
  }
  private apply(data: History): void {
    this.quotes.set(data.quotes);
    this.select(data.quotes[0] ?? null);
    this.versionChanged.emit(data.workOrderVersion);
    if (this.startRevisionOnLoad && this.canEdit()) {
      this.startRevisionOnLoad = false;
      if (!data.quotes.some((quote) => quote.status === 'DRAFT')) this.newVersion();
    }
  }
  protected select(quote: Quote | null): void {
    this.revisionOpen.set(false);
    this.linkAction.set(null);
    this.selected.set(quote);
    this.lines = quote ? quote.items.map((item) => ({ ...item })) : [];
    this.discountType = quote?.discountType ?? 'FIXED';
    this.discountValue = quote?.discountValue ?? 0;
    this.notice.set('');
  }
  protected newVersion(): void {
    if (
      !this.canEdit() ||
      this.busy() ||
      this.blocked() ||
      this.quotes().some((quote) => quote.status === 'DRAFT')
    )
      return;
    const source = this.selected();
    this.select(null);
    if (source) {
      this.lines = source.items.map((item) => ({ ...item }));
      this.discountType = source.discountType;
      this.discountValue = source.discountValue;
    }
    this.notice.set('Nova versão em edição. Salve para calcular o total.');
  }
  protected loadParts(): void {
    const id = this.order().id;
    this.partsError.set('');
    this.http
      .get<Part[]>('/api/v1/parts')
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (parts) => {
          if (this.order().id === id) this.parts.set(parts);
        },
        error: () => {
          if (this.order().id === id) this.partsError.set('Não foi possível carregar as peças.');
        },
      });
  }
  protected selectPart(item: Item): void {
    const part = this.parts().find((part) => part.id === item.partId);
    if (part) {
      item.description = part.name;
      item.unitPrice = part.salePrice;
    }
  }
  protected changeType(item: Item): void {
    item.partId = null;
  }
  protected addLine(): void {
    if (this.lines.length < 100)
      this.lines.push({
        type: 'SERVICE',
        partId: null,
        description: '',
        quantity: 1,
        unitPrice: 0,
      });
  }
  protected removeLine(index: number): void {
    this.lines.splice(index, 1);
  }
  protected statusLabel(status: string): string {
    return (
      (
        {
          DRAFT: 'Rascunho',
          SENT: 'Enviado',
          APPROVED: 'Aprovado',
          REJECTED: 'Rejeitado',
        } as Record<string, string>
      )[status] ?? status
    );
  }
  protected save(): void {
    if (!this.editing() || !this.loaded() || this.busy() || this.blocked() || !this.lines.length)
      return;
    const id = this.order().id;
    const request = ++this.request;
    const quote = this.selected();
    const body = {
      workOrderVersion: this.order().version,
      items: this.lines.map((item) => ({
        ...item,
        partId: item.type === 'PART' ? item.partId : null,
      })),
      discountType: this.discountType,
      discountValue: this.discountValue,
    };
    this.busy.set(true);
    this.busyChanged.emit(true);
    this.error.set('');
    this.notice.set('');
    const url = `/api/v1/work-orders/${id}/quotes`;
    const operation = quote
      ? this.http.put<History>(`${url}/${quote.id}`, body)
      : this.http.post<History>(url, body);
    operation.pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: (data) => {
        if (request !== this.request) return;
        this.apply(data);
        this.busy.set(false);
        this.busyChanged.emit(false);
        this.saved.emit();
        this.pendingSend = null;
        this.notice.set('Rascunho salvo. Totais calculados pelo servidor.');
      },
      error: (error) => {
        if (request !== this.request) return;
        this.busy.set(false);
        this.busyChanged.emit(false);
        this.error.set(
          error.status < 500 && typeof error.error?.detail === 'string'
            ? error.error.detail
            : 'Não foi possível salvar o orçamento. Tente novamente.',
        );
        if (error.status === 409)
          this.notice.set(
            'Recarregue o orçamento para conferir a versão atual antes de tentar novamente.',
          );
      },
    });
  }
}
