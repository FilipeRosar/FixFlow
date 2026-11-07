import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withInterceptors, HttpHeaders } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute } from '@angular/router';
import { Meta } from '@angular/platform-browser';
import { BehaviorSubject } from 'rxjs';
import { PublicQuote } from './public-quote';
import { sessionInterceptor } from '../../core/auth/interceptors/session.interceptor';
import { SessionService } from '../../core/auth/data-access/session.service';

const token = 'A'.repeat(43);
const view = {
  companyName: 'Oficina Horizonte',
  workOrderNumber: 42,
  equipmentLabel: 'Dell Inspiron',
  workOrderStatus: 'AGUARDANDO_APROVACAO',
  readOnly: false,
  contentHash: 'a'.repeat(64),
  decision: null,
  quote: {
    version: 2,
    status: 'SENT',
    laborSubtotal: 150,
    partsSubtotal: 70,
    discountAmount: 20,
    total: 200,
    validUntil: '2026-11-15T12:00:00Z',
    warrantyTerms: 'Garantia de 90 dias.',
    items: [
      { type: 'SERVICE', description: 'Reparo da placa', quantity: 1, unitPrice: 150 },
      { type: 'PART', description: 'Fonte', quantity: 1, unitPrice: 70 },
    ],
  },
};
describe('Public quote', () => {
  let fragments: BehaviorSubject<string | null>;
  const ensureSession = vi.fn();
  beforeEach(() => {
    ensureSession.mockReset();
    fragments = new BehaviorSubject<string | null>('token=' + token);
    TestBed.configureTestingModule({
      imports: [PublicQuote],
      providers: [
        provideHttpClient(withInterceptors([sessionInterceptor])),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { fragment: fragments } },
        { provide: SessionService, useValue: { ensureSession } },
      ],
    });
  });
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    expect(ensureSession).not.toHaveBeenCalled();
  });
  function setup() {
    const fixture = TestBed.createComponent(PublicQuote);
    fixture.detectChanges();
    return fixture;
  }
  function receipt(decision = 'APPROVED') {
    return { decision, workOrderStatus: decision === 'APPROVED' ? 'APROVADA' : 'ORCAMENTO_REJEITADO', quoteVersion: 2, decidedAt: '2026-11-06T18:30:00Z', contentHash: view.contentHash };
  }
  function loaded() {
    const fixture = setup(); TestBed.inject(HttpTestingController).expectOne('/public/v1/work-orders/' + token).flush(view); fixture.detectChanges(); return fixture;
  }
  it('requires explicit confirmation before posting approval and then becomes read-only', () => {
    const fixture = loaded(); const http = TestBed.inject(HttpTestingController);
    fixture.componentInstance['choose']('APPROVED'); fixture.componentInstance['decide']();
    http.expectNone('/public/v1/work-orders/' + token + '/approve');
    fixture.componentInstance['confirmed'] = true; fixture.componentInstance['decide']();
    const request = http.expectOne('/public/v1/work-orders/' + token + '/approve');
    expect(request.request.body).toEqual({ confirmed: true, contentHash: view.contentHash, reason: null });
    expect(request.request.headers.get('Idempotency-Key')).toBeTruthy();
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush(receipt()); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Aprovação registrada');
    expect(fixture.nativeElement.textContent).toContain('Decisão registrada');
    expect(fixture.nativeElement.querySelector('form')).toBeNull();
    fixture.componentInstance['decide']();
  });
  it('posts a rejection with the optional reason and displays its receipt', () => {
    const fixture = loaded(); fixture.componentInstance['choose']('REJECTED');
    fixture.componentInstance['reason'] = ' Prefiro não fazer o reparo '; fixture.componentInstance['confirmed'] = true; fixture.componentInstance['decide']();
    const request = TestBed.inject(HttpTestingController).expectOne('/public/v1/work-orders/' + token + '/reject');
    expect(request.request.body.reason).toBe('Prefiro não fazer o reparo');
    request.flush(receipt('REJECTED')); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Rejeição registrada');
    expect(fixture.nativeElement.textContent).toContain('Prefiro não fazer o reparo');
  });
  it('recovers an uncertain decision using the same idempotency key', () => {
    const fixture = loaded(); const http = TestBed.inject(HttpTestingController);
    fixture.componentInstance['choose']('APPROVED'); fixture.componentInstance['confirmed'] = true; fixture.componentInstance['decide']();
    const first = http.expectOne('/public/v1/work-orders/' + token + '/approve'); const key = first.request.headers.get('Idempotency-Key');
    first.flush({}, { status: 500, statusText: 'Unknown result' }); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('recuperar a mesma decisão');
    fixture.componentInstance['decide'](); const retry = http.expectOne('/public/v1/work-orders/' + token + '/approve');
    expect(retry.request.headers.get('Idempotency-Key')).toBe(key); retry.flush(receipt());
  });
  it('reloads after a content or concurrent decision conflict instead of repeating the mutation', () => {
    const fixture = loaded(); const http = TestBed.inject(HttpTestingController);
    fixture.componentInstance['choose']('APPROVED'); fixture.componentInstance['confirmed'] = true; fixture.componentInstance['decide']();
    http.expectOne('/public/v1/work-orders/' + token + '/approve').flush({ code: 'QUOTE_CONTENT_CHANGED', detail: 'A proposta mudou.' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges(); expect(fixture.nativeElement.textContent).toContain('A proposta mudou');
    fixture.componentInstance['decide'](); fixture.componentInstance['load']();
    http.expectOne('/public/v1/work-orders/' + token).flush({ ...view, readOnly: true, quote: { ...view.quote, status: 'APPROVED' }, decision: receipt() });
  });
  it('respects the rate limit when confirming a decision', () => {
    const fixture = loaded(); fixture.componentInstance['choose']('APPROVED'); fixture.componentInstance['confirmed'] = true; fixture.componentInstance['decide']();
    TestBed.inject(HttpTestingController).expectOne('/public/v1/work-orders/' + token + '/approve').flush({}, { status: 429, statusText: 'Too Many Requests', headers: new HttpHeaders({ 'Retry-After': '45' }) });
    fixture.detectChanges(); expect(fixture.nativeElement.textContent).toContain('45 s');
    fixture.componentInstance['decide']();
  });
  it('does not apply a decision response to a different public link', () => {
    const fixture = loaded(); const http = TestBed.inject(HttpTestingController);
    fixture.componentInstance['choose']('APPROVED'); fixture.componentInstance['confirmed'] = true; fixture.componentInstance['decide']();
    const pending = http.expectOne('/public/v1/work-orders/' + token + '/approve');
    fragments.next('token=' + 'B'.repeat(43)); expect(pending.cancelled).toBe(true);
    http.expectOne('/public/v1/work-orders/' + 'B'.repeat(43)).flush({ ...view, companyName: 'Outra assistência' }); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).not.toContain('Aprovação registrada');
    expect(fixture.nativeElement.textContent).toContain('Outra assistência');
  });
  it('uses the emailed fragment anonymously and displays only server-provided values', () => {
    const fixture = setup();
    const request = TestBed.inject(HttpTestingController).expectOne(
      '/public/v1/work-orders/' + token,
    );
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush(view);
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent;
    for (const value of [
      'Oficina Horizonte',
      '42',
      'Dell Inspiron',
      'Reparo da placa',
      'Fonte',
      '200,00',
      'Garantia de 90 dias.',
    ])
      expect(text).toContain(value);
    expect(text).not.toContain(token);
    expect(fixture.nativeElement.querySelector('form')).toBeNull();
  });
  it('shows the same unavailable message for missing and invalid fragments without querying the API', () => {
    fragments.next(null);
    const fixture = setup();
    expect(fixture.nativeElement.textContent).toContain(
      'Orçamento não encontrado ou indisponível.',
    );
    fragments.next('token=bad');
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain(
      'Orçamento não encontrado ou indisponível.',
    );
  });
  it('shows the generic unavailable response for expired or revoked tokens', () => {
    const fixture = setup();
    TestBed.inject(HttpTestingController)
      .expectOne('/public/v1/work-orders/' + token)
      .flush({ status: 404 }, { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain(
      'Orçamento não encontrado ou indisponível.',
    );
    expect(fixture.nativeElement.textContent).not.toContain('Oficina Horizonte');
  });
  it('respects Retry-After without automatically requesting the page again', () => {
    const fixture = setup();
    TestBed.inject(HttpTestingController)
      .expectOne('/public/v1/work-orders/' + token)
      .flush(
        {},
        {
          status: 429,
          statusText: 'Too Many Requests',
          headers: new HttpHeaders({ 'Retry-After': '30' }),
        },
      );
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('button').disabled).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('30 s');
    fixture.componentInstance['load']();
  });
  it('discards the previous proposal immediately when the fragment changes', () => {
    const fixture = setup();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/public/v1/work-orders/' + token).flush(view);
    fixture.detectChanges();
    fragments.next('token=' + 'B'.repeat(43));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).not.toContain('Oficina Horizonte');
    http
      .expectOne('/public/v1/work-orders/' + 'B'.repeat(43))
      .flush({}, { status: 404, statusText: 'Not Found' });
  });
  it('cancels a pending request when navigating to a different link', () => {
    setup();
    const http = TestBed.inject(HttpTestingController);
    const previous = http.expectOne('/public/v1/work-orders/' + token);
    fragments.next('token=' + 'C'.repeat(43));
    expect(previous.cancelled).toBe(true);
    http.expectOne('/public/v1/work-orders/' + 'C'.repeat(43)).flush(view);
  });
  it('renders free text literally and marks an approved proposal as read-only', () => {
    const fixture = setup();
    TestBed.inject(HttpTestingController)
      .expectOne('/public/v1/work-orders/' + token)
      .flush({
        ...view,
        readOnly: true,
        quote: { ...view.quote, status: 'APPROVED', warrantyTerms: '<img src=x onerror=alert(1)>' },
      });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Aprovado');
    expect(fixture.nativeElement.textContent).toContain('já foi decidida');
    expect(fixture.nativeElement.querySelector('img')).toBeNull();
    expect(fixture.nativeElement.querySelector('button')).toBeNull();
  });
  it('sets no-index and no-referrer meta and removes them after leaving the page', () => {
    const meta = TestBed.inject(Meta);
    meta.removeTag('name="robots"');
    meta.removeTag('name="referrer"');
    const fixture = setup();
    TestBed.inject(HttpTestingController)
      .expectOne('/public/v1/work-orders/' + token)
      .flush(view);
    expect(meta.getTag('name="robots"')?.content).toContain('noindex');
    expect(meta.getTag('name="referrer"')?.content).toBe('no-referrer');
    fixture.destroy();
    expect(meta.getTag('name="robots"')).toBeNull();
    expect(meta.getTag('name="referrer"')).toBeNull();
  });
});
