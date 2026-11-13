import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { SessionService } from '../../../../core/auth/data-access/session.service';
import { WorkOrderQuotePanel } from './work-order-quote';

const order = { id: 'os-1', version: 4, status: 'AGUARDANDO_ORCAMENTO' };
const draft = {
  id: 'quote-1',
  version: 1,
  status: 'DRAFT',
  items: [{ type: 'SERVICE', partId: null, description: 'Reparo', quantity: 2, unitPrice: 100 }],
  discountType: 'FIXED',
  discountValue: 20,
  laborSubtotal: 200,
  partsSubtotal: 0,
  discountAmount: 20,
  total: 180,
  validUntil: '2026-11-15T12:00:00Z',
  warrantyTerms: 'Garantia registrada',
  sentAt: null,
};
describe('Work order quote', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [WorkOrderQuotePanel],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: SessionService, useValue: { session: signal({ role: 'OWNER' }) } },
      ],
    }),
  );
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  function setup(status = order.status) {
    const fixture = TestBed.createComponent(WorkOrderQuotePanel);
    fixture.componentRef.setInput('order', { ...order, status });
    fixture.detectChanges();
    fixture.nativeElement.querySelector('button').click();
    fixture.detectChanges();
    return fixture;
  }
  function load(fixture: ReturnType<typeof setup>, quotes: object[], version = 4) {
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/work-orders/os-1/quotes').flush({ quotes, workOrderVersion: version });
    if (fixture.componentInstance.order().status === 'AGUARDANDO_ORCAMENTO')
      http.expectOne('/api/v1/parts').flush([{ id: 'part-1', name: 'Fonte', salePrice: 70 }]);
    fixture.detectChanges();
  }
  function button(fixture: ReturnType<typeof setup>, text: string): HTMLButtonElement {
    return [...fixture.nativeElement.querySelectorAll('button')].find((item: any) =>
      item.textContent.includes(text),
    ) as HTMLButtonElement;
  }
  function linkReceipt(linkStatus: string, status = 'AGUARDANDO_APROVACAO') {
    return {
      workOrderId: 'os-1',
      quoteId: 'quote-1',
      workOrderVersion: 5,
      status,
      notificationStatus: linkStatus === 'ACTIVE' ? 'QUEUED' : 'NONE',
      validUntil: draft.validUntil,
      linkStatus,
    };
  }
  it('opens a sent quote for editing as a new version and preserves its previous values', () => {
    const fixture = setup('AGUARDANDO_APROVACAO');
    const original = { ...draft, status: 'SENT', linkStatus: 'ACTIVE' };
    load(fixture, [original]);
    const http = TestBed.inject(HttpTestingController);
    fixture.componentInstance.revised.subscribe((receipt) => {
      fixture.componentRef.setInput('order', {
        id: receipt.workOrderId,
        version: receipt.workOrderVersion,
        status: receipt.status,
      });
    });
    button(fixture, 'Editar orçamento').click();
    fixture.detectChanges();
    http.expectNone((request) => request.method === 'PATCH');
    expect(fixture.nativeElement.textContent).toContain('O link anterior será invalidado');
    button(fixture, 'Confirmar edição').click();
    const transition = http.expectOne('/api/v1/work-orders/os-1/status');
    expect(transition.request.method).toBe('PATCH');
    expect(transition.request.body).toEqual({
      toStatus: 'AGUARDANDO_ORCAMENTO',
      version: 4,
      reason: 'Revisão do orçamento para edição.',
      paymentOverride: false,
    });
    transition.flush({ id: 'os-1', version: 5, status: 'AGUARDANDO_ORCAMENTO' });
    fixture.detectChanges();
    http
      .expectOne('/api/v1/work-orders/os-1/quotes')
      .flush({ quotes: [{ ...original, linkStatus: 'REVOKED' }], workOrderVersion: 5 });
    http.expectOne('/api/v1/parts').flush([]);
    fixture.detectChanges();
    expect(fixture.componentInstance['selected']()).toBeNull();
    expect(fixture.componentInstance['lines']).toEqual(draft.items);
    fixture.componentInstance['lines'][0].unitPrice = 150;
    expect(fixture.componentInstance['quotes']()[0].items[0].unitPrice).toBe(100);
    fixture.componentInstance['save']();
    const save = http.expectOne('/api/v1/work-orders/os-1/quotes');
    expect(save.request.method).toBe('POST');
    expect(save.request.body.workOrderVersion).toBe(5);
    expect(save.request.body.items[0].unitPrice).toBe(150);
    save.flush({
      quotes: [{ ...draft, id: 'quote-2', version: 2 }, original],
      workOrderVersion: 6,
    });
  });
  it('allows revising rejected quotes but keeps approved quotes unchanged', () => {
    const fixture = setup('ORCAMENTO_REJEITADO');
    load(fixture, [{ ...draft, status: 'REJECTED' }]);
    expect(button(fixture, 'Editar orçamento')).toBeDefined();
    fixture.componentRef.setInput('order', { ...order, status: 'APROVADA' });
    fixture.detectChanges();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/work-orders/os-1/quotes')
      .flush({ quotes: [{ ...draft, status: 'APPROVED' }], workOrderVersion: 4 });
    fixture.detectChanges();
    expect(button(fixture, 'Editar orçamento')).toBeUndefined();
  });
  it('keeps the original quote when revision conflicts with another update', () => {
    const fixture = setup('AGUARDANDO_APROVACAO');
    load(fixture, [{ ...draft, status: 'SENT' }]);
    button(fixture, 'Editar orçamento').click();
    fixture.detectChanges();
    button(fixture, 'Confirmar edição').click();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/work-orders/os-1/status')
      .flush({ detail: 'A OS foi alterada. Recarregue.' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('A OS foi alterada. Recarregue.');
    expect(fixture.componentInstance['selected']()?.status).toBe('SENT');
    expect(fixture.componentInstance['busy']()).toBe(false);
  });
  it('requires confirmation before rotating a sent link and sends the version and idempotency key', () => {
    const fixture = setup('AGUARDANDO_APROVACAO');
    load(fixture, [{ ...draft, status: 'SENT', linkStatus: 'ACTIVE' }]);
    button(fixture, 'Reenviar novo link').click();
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectNone('/api/v1/work-orders/os-1/quotes/quote-1/resend-link');
    expect(fixture.nativeElement.textContent).toContain('O link atual deixará de funcionar');
    button(fixture, 'Confirmar reenvio').click();
    const request = http.expectOne('/api/v1/work-orders/os-1/quotes/quote-1/resend-link');
    expect(request.request.body).toEqual({ workOrderVersion: 4 });
    expect(request.request.headers.get('Idempotency-Key')).toBeTruthy();
    request.flush(linkReceipt('ACTIVE'));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('O link anterior foi invalidado');
  });
  it('revokes the link without changing the proposal or requesting an email', () => {
    const fixture = setup('AGUARDANDO_APROVACAO');
    load(fixture, [{ ...draft, status: 'SENT', linkStatus: 'ACTIVE' }]);
    fixture.componentInstance['chooseLinkAction']('revoke-link');
    fixture.componentInstance['manageLink']();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/work-orders/os-1/quotes/quote-1/revoke-link')
      .flush(linkReceipt('REVOKED'));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Link revogado');
    expect(button(fixture, 'Revogar link').disabled).toBe(true);
    expect(fixture.componentInstance['selected']()?.status).toBe('SENT');
  });
  it('recovers an uncertain reissue using the same key without duplicating the operation', () => {
    const fixture = setup('AGUARDANDO_APROVACAO');
    load(fixture, [{ ...draft, status: 'SENT', linkStatus: 'ACTIVE' }]);
    const http = TestBed.inject(HttpTestingController);
    fixture.componentInstance['chooseLinkAction']('resend-link');
    fixture.componentInstance['manageLink']();
    const first = http.expectOne('/api/v1/work-orders/os-1/quotes/quote-1/resend-link');
    const key = first.request.headers.get('Idempotency-Key');
    first.flush({}, { status: 500, statusText: 'Unknown result' });
    fixture.componentInstance['manageLink']();
    const retry = http.expectOne('/api/v1/work-orders/os-1/quotes/quote-1/resend-link');
    expect(retry.request.headers.get('Idempotency-Key')).toBe(key);
    retry.flush(linkReceipt('ACTIVE'));
  });
  it('keeps an approved proposal read-only when rotating its consultation link', () => {
    const fixture = setup('APROVADA');
    load(fixture, [{ ...draft, status: 'APPROVED', linkStatus: 'REVOKED' }]);
    fixture.componentInstance['chooseLinkAction']('resend-link');
    fixture.componentInstance['manageLink']();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/work-orders/os-1/quotes/quote-1/resend-link')
      .flush({ ...linkReceipt('ACTIVE', 'APROVADA'), validUntil: '2027-02-01T12:00:00Z' });
    fixture.detectChanges();
    expect(fixture.componentInstance['selected']()?.status).toBe('APPROVED');
    expect(fixture.componentInstance['selected']()?.validUntil).toBe(draft.validUntil);
    expect(fixture.nativeElement.querySelector('form')).toBeNull();
  });
  it('does not manage links for technicians', () => {
    TestBed.overrideProvider(SessionService, {
      useValue: { session: signal({ role: 'TECHNICIAN' }) },
    });
    const fixture = setup('AGUARDANDO_APROVACAO');
    load(fixture, [{ ...draft, status: 'SENT', linkStatus: 'ACTIVE' }]);
    expect(button(fixture, 'Reenviar novo link')).toBeUndefined();
    fixture.componentInstance['chooseLinkAction']('resend-link');
    fixture.componentInstance['manageLink']();
  });
  it('reuses the same idempotency key after an uncertain send and emits the new OS state', () => {
    const fixture = setup();
    load(fixture, [draft]);
    const receipts: any[] = [];
    fixture.componentInstance.sent.subscribe((receipt) => receipts.push(receipt));
    fixture.componentInstance['sendQuote']();
    const http = TestBed.inject(HttpTestingController);
    const first = http.expectOne('/api/v1/work-orders/os-1/quotes/quote-1/send');
    const key = first.request.headers.get('Idempotency-Key');
    expect(key).toBeTruthy();
    expect(first.request.body).toEqual({ workOrderVersion: 4 });
    first.flush({}, { status: 500, statusText: 'Uncertain result' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('mesma solicitação');
    fixture.componentInstance['sendQuote']();
    const retry = http.expectOne('/api/v1/work-orders/os-1/quotes/quote-1/send');
    expect(retry.request.headers.get('Idempotency-Key')).toBe(key);
    retry.flush({
      workOrderId: 'os-1',
      quoteId: 'quote-1',
      workOrderVersion: 5,
      status: 'AGUARDANDO_APROVACAO',
      notificationStatus: 'QUEUED',
      validUntil: draft.validUntil,
    });
    fixture.detectChanges();
    expect(receipts[0].status).toBe('AGUARDANDO_APROVACAO');
    expect(fixture.nativeElement.textContent).toContain('e-mail está na fila');
    expect(button(fixture, 'Enviar orçamento')).toBeUndefined();
  });
  it('requires unsaved edits to be saved before sending', async () => {
    const fixture = setup();
    load(fixture, [draft]);
    await fixture.whenStable();
    const price = fixture.nativeElement.querySelectorAll(
      '.quote-line input',
    )[2] as HTMLInputElement;
    price.value = '123';
    price.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    fixture.detectChanges();
    expect(button(fixture, 'Enviar orçamento').disabled).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('Salve suas alterações');
    fixture.componentInstance['sendQuote']();
  });
  it('does not offer sending to technicians', () => {
    TestBed.overrideProvider(SessionService, {
      useValue: { session: signal({ role: 'TECHNICIAN' }) },
    });
    const fixture = setup();
    load(fixture, [draft]);
    expect(button(fixture, 'Enviar orçamento')).toBeUndefined();
    fixture.componentInstance['sendQuote']();
  });
  it('displays server totals and sends draft changes without accepting a client total', async () => {
    const fixture = setup();
    load(fixture, [draft]);
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('180,00');
    const price = fixture.nativeElement.querySelectorAll(
      '.quote-line input',
    )[2] as HTMLInputElement;
    price.value = '250';
    price.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('180,00');
    fixture.componentInstance['save']();
    const request = TestBed.inject(HttpTestingController).expectOne(
      '/api/v1/work-orders/os-1/quotes/quote-1',
    );
    expect(request.request.method).toBe('PUT');
    expect(request.request.body.items[0].unitPrice).toBe(250);
    expect(request.request.body.workOrderVersion).toBe(4);
    expect(request.request.body.total).toBeUndefined();
    request.flush({ quotes: [{ ...draft, laborSubtotal: 500, total: 480 }], workOrderVersion: 5 });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('480,00');
    expect(fixture.nativeElement.textContent).toContain('Totais calculados pelo servidor');
  });
  it('creates the first draft with the selected part price and percentage discount', () => {
    const fixture = setup();
    load(fixture, []);
    fixture.componentInstance['addLine']();
    const item = fixture.componentInstance['lines'][0];
    item.type = 'PART';
    item.partId = 'part-1';
    fixture.componentInstance['selectPart'](item);
    fixture.componentInstance['discountType'] = 'PERCENT';
    fixture.componentInstance['discountValue'] = 10;
    fixture.componentInstance['save']();
    const request = TestBed.inject(HttpTestingController).expectOne(
      '/api/v1/work-orders/os-1/quotes',
    );
    expect(request.request.method).toBe('POST');
    expect(request.request.body.items).toEqual([
      { type: 'PART', partId: 'part-1', description: 'Fonte', quantity: 1, unitPrice: 70 },
    ]);
    expect(request.request.body.discountType).toBe('PERCENT');
    expect(request.request.body.discountValue).toBe(10);
    request.flush({ quotes: [draft], workOrderVersion: 5 });
  });
  it('renders a sent version read-only with its saved terms and prices', () => {
    const fixture = setup('AGUARDANDO_APROVACAO');
    load(fixture, [{ ...draft, status: 'SENT', sentAt: '2026-11-08T12:00:00Z' }]);
    expect(fixture.nativeElement.querySelector('form')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Enviado');
    expect(fixture.nativeElement.textContent).toContain('Garantia registrada');
    expect(fixture.nativeElement.textContent).toContain('Reparo');
    expect(button(fixture, 'Criar nova versão')).toBeUndefined();
    fixture.componentInstance['save']();
  });
  it('copies a sent version into a new draft and preserves the historical version', async () => {
    const fixture = setup();
    load(fixture, [{ ...draft, status: 'SENT' }], 7);
    fixture.componentRef.setInput('order', { ...order, version: 7 });
    fixture.detectChanges();
    button(fixture, 'Criar nova versão').click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('.quote-line input').value).toBe('Reparo');
    fixture.componentInstance['save']();
    const request = TestBed.inject(HttpTestingController).expectOne(
      '/api/v1/work-orders/os-1/quotes',
    );
    expect(request.request.method).toBe('POST');
    expect(request.request.body.workOrderVersion).toBe(7);
    request.flush({
      quotes: [
        { ...draft, id: 'quote-2', version: 2 },
        { ...draft, status: 'SENT' },
      ],
      workOrderVersion: 8,
    });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Versão 2');
    expect(fixture.nativeElement.textContent).toContain('Versão 1');
  });
  it('keeps the form and explains how to recover from a version conflict', () => {
    const fixture = setup();
    load(fixture, [draft]);
    fixture.componentInstance['lines'][0].description = 'Descrição editada';
    fixture.componentInstance['save']();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/work-orders/os-1/quotes/quote-1')
      .flush({ detail: 'Versão desatualizada' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Versão desatualizada');
    expect(fixture.nativeElement.textContent).toContain('Recarregue o orçamento');
    expect(fixture.componentInstance['lines'][0].description).toBe('Descrição editada');
    expect(button(fixture, 'Salvar e calcular').disabled).toBe(false);
  });
  it('ignores responses from a previous work order after navigation', () => {
    const fixture = setup();
    const request = TestBed.inject(HttpTestingController).expectOne(
      '/api/v1/work-orders/os-1/quotes',
    );
    fixture.componentRef.setInput('order', { ...order, id: 'os-2' });
    fixture.detectChanges();
    request.flush({ quotes: [draft], workOrderVersion: 40 });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).not.toContain('Reparo');
    expect(fixture.componentInstance['quotes']()).toEqual([]);
  });
  it('handles load errors with a retry and blocks writes during another OS operation', () => {
    const fixture = setup();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/work-orders/os-1/quotes')
      .flush({}, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Não foi possível carregar');
    button(fixture, 'Recarregar').click();
    load(fixture, [draft]);
    fixture.componentRef.setInput('blocked', true);
    fixture.detectChanges();
    fixture.componentInstance['save']();
    expect(fixture.nativeElement.querySelector('fieldset').disabled).toBe(true);
  });
});
