import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { WorkOrderTimelinePanel } from './work-order-timeline';
const entry = {
  id: 'event',
  eventType: 'WORK_ORDER_STATUS_CHANGED',
  actorId: 'user',
  actorName: 'Ana Souza',
  occurredAt: '2026-11-05T12:00:00Z',
  origin: 'USER',
  fromStatus: 'RECEBIDA',
  toStatus: 'CANCELADA',
  reason: 'Cliente desistiu\nApós contato telefônico.',
  details: {},
};
describe('Work order timeline', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [WorkOrderTimelinePanel],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }),
  );
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  function setup() {
    const fixture = TestBed.createComponent(WorkOrderTimelinePanel);
    fixture.componentRef.setInput('orderId', 'order');
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectNone((r) => r.url.endsWith('/timeline'));
    fixture.componentInstance['open'].set(true);
    fixture.detectChanges();
    const request = http.expectOne((r) => r.url === '/api/v1/work-orders/order/timeline');
    return {
      fixture,
      http,
      request,
      component: fixture.componentInstance,
      page: fixture.nativeElement as HTMLElement,
    };
  }
  function response(content = [entry], page = 0, totalElements = 1) {
    return { content, page, totalElements, size: 20 };
  }
  it('shows the original author, timestamp, transition and exact reason without editing controls', () => {
    const { fixture, request, page } = setup();
    request.flush(response());
    fixture.detectChanges();
    expect(page.textContent).toContain('Ana Souza');
    expect(page.querySelector('time')?.getAttribute('datetime')).toBe(entry.occurredAt);
    expect(page.textContent).toContain('Recebida');
    expect(page.textContent).toContain('Cancelada');
    expect(page.querySelector('.event-reason p')?.textContent).toBe(entry.reason);
    expect(page.querySelector('input, textarea, select')).toBeNull();
  });
  it('renders user-entered reasons as text rather than HTML', () => {
    const { fixture, request, page } = setup();
    request.flush(response([{ ...entry, reason: '<b>Motivo original</b>' }]));
    fixture.detectChanges();
    expect(page.querySelector('.event-reason p')?.textContent).toBe('<b>Motivo original</b>');
    expect(page.querySelector('.event-reason b')).toBeNull();
  });
  it('loads the next page and disables navigation at the boundaries', () => {
    const { fixture, request, component, http, page } = setup();
    request.flush(response([entry], 0, 21));
    fixture.detectChanges();
    expect(page.querySelector<HTMLButtonElement>('.timeline-pagination button')?.disabled).toBe(
      true,
    );
    component['load'](1);
    http
      .expectOne((r) => r.params.get('page') === '1')
      .flush(response([{ ...entry, id: 'older' }], 1, 21));
    fixture.detectChanges();
    expect(
      page.querySelector<HTMLButtonElement>('.timeline-pagination button:last-child')?.disabled,
    ).toBe(true);
    expect(page.textContent).toContain('Página 2');
  });
  it('refreshes an open timeline when the order version or attachment revision changes', () => {
    const { fixture, request, http } = setup();
    request.flush(response());
    fixture.detectChanges();
    fixture.componentRef.setInput('version', 2);
    fixture.detectChanges();
    http
      .expectOne((r) => r.url.endsWith('/timeline') && r.params.get('page') === '0')
      .flush(response());
    fixture.componentRef.setInput('revision', 1);
    fixture.detectChanges();
    http.expectOne((r) => r.url.endsWith('/timeline')).flush(response());
  });
  it('allows retry after a failure and shows a real empty state', () => {
    const { fixture, request, component, http, page } = setup();
    request.flush({}, { status: 500, statusText: 'Error' });
    fixture.detectChanges();
    expect(page.querySelector('[role="alert"]')).not.toBeNull();
    component['load']();
    http.expectOne((r) => r.url.endsWith('/timeline')).flush(response([], 0, 0));
    fixture.detectChanges();
    expect(page.querySelector('[role="alert"]')).toBeNull();
    expect(page.textContent).toContain('Nenhum evento registrado');
  });
  it('cancels an obsolete request when a new order version is received', () => {
    const { fixture, request, http } = setup();
    fixture.componentRef.setInput('version', 1);
    fixture.detectChanges();
    expect(request.cancelled).toBe(true);
    http.expectOne((r) => r.url.endsWith('/timeline')).flush(response());
    fixture.detectChanges();
  });
  it('identifies the delivery payment exception and additional warranty in the timeline', () => {
    const { fixture, request, page } = setup();
    request.flush(
      response([
        {
          ...entry,
          toStatus: 'ENTREGUE',
          details: { paymentOverride: true, warrantyDays: 90, warrantyUntil: '2027-02-03' },
        },
      ]),
    );
    fixture.detectChanges();
    expect(page.querySelector('.event-payment-exception')?.textContent).toContain(
      'pagamento pendente',
    );
    expect(page.textContent).toContain('90 dias');
    expect(page.textContent).toContain('03/02/2027');
  });
});
