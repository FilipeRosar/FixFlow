import { SessionService } from '../../../../core/auth/data-access/session.service';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { WorkOrderPaymentsPanel } from './work-order-payments';

describe('Work order payments', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [WorkOrderPaymentsPanel],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: SessionService, useValue: { session: () => ({ role: 'OWNER' }) } },
      ],
    }),
  );
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  it('reuses the key and complete body after an uncertain response', () => {
    const fixture = TestBed.createComponent(WorkOrderPaymentsPanel);
    fixture.componentRef.setInput('order', { id: 'order', financialStatus: 'PARTIAL' });
    fixture.detectChanges();
    const component = fixture.componentInstance;
    const http = TestBed.inject(HttpTestingController);
    component['amount'] = 40;
    component['save']();
    const first = http.expectOne('/api/v1/work-orders/order/payments');
    const key = first.request.headers.get('Idempotency-Key');
    const body = first.request.body;
    first.flush({}, { status: 500, statusText: 'Error' });
    component['save']();
    const retry = http.expectOne('/api/v1/work-orders/order/payments');
    expect(retry.request.headers.get('Idempotency-Key')).toBe(key);
    expect(retry.request.body).toEqual(body);
    retry.flush({ id: 'payment', ...body });
    http.expectOne('/api/v1/work-orders/order/payments').flush([{ id: 'payment', ...body }]);
    expect(component['payments']().length).toBe(1);
    expect(component['pending']).toBeNull();
    expect(component['notice']()).toBe('Pagamento registrado.');
  });
  function setupPayments() {
    const fixture = TestBed.createComponent(WorkOrderPaymentsPanel);
    fixture.componentRef.setInput('order', { id: 'order', financialStatus: 'PAID' });
    fixture.detectChanges();
    const component = fixture.componentInstance;
    const http = TestBed.inject(HttpTestingController);
    component['toggle']();
    const payment = {
      id: 'payment',
      workOrderId: 'order',
      amount: 100,
      method: 'PIX',
      status: 'CONFIRMED',
      paidAt: '2026-11-08T12:00:00Z',
      notes: null,
    };
    http.expectOne('/api/v1/work-orders/order/payments').flush([payment]);
    fixture.detectChanges();
    return { fixture, component, http, payment };
  }
  it('requires a reason and confirmation, preserving the payment and excluding it from the total', () => {
    const { fixture, component, http, payment } = setupPayments();
    const refreshed = vi.fn();
    component.registered.subscribe(refreshed);
    (fixture.nativeElement.querySelector('.void-button') as HTMLButtonElement).click();
    fixture.detectChanges();
    component['confirmVoid']();
    http.expectNone('/api/v1/payments/payment/void');
    component['voidReason'] = '  Lançamento duplicado  ';
    component['confirmVoid']();
    const request = http.expectOne('/api/v1/payments/payment/void');
    expect(request.request.body).toEqual({ reason: 'Lançamento duplicado' });
    request.flush({
      ...payment,
      status: 'VOIDED',
      voidReason: 'Lançamento duplicado',
      voidedAt: '2026-11-08T13:00:00Z',
    });
    fixture.detectChanges();
    expect(component['payments']().length).toBe(1);
    expect(component['confirmedTotal']()).toBe(0);
    expect(fixture.nativeElement.textContent).toContain('Lançamento duplicado');
    expect(fixture.nativeElement.querySelector('.void-button')).toBeNull();
    expect(refreshed).toHaveBeenCalledWith('order');
  });
  it('retains the same reason after an uncertain void response', () => {
    const { component, http, payment } = setupPayments();
    component['beginVoid'](payment);
    component['voidReason'] = 'Duplicado';
    component['confirmVoid']();
    http.expectOne('/api/v1/payments/payment/void').flush({}, { status: 500, statusText: 'Error' });
    component['cancelVoid']();
    expect(component['voidTarget']()).toBe('payment');
    component['voidReason'] = 'Outro motivo';
    component['confirmVoid']();
    const retry = http.expectOne('/api/v1/payments/payment/void');
    expect(retry.request.body).toEqual({ reason: 'Duplicado' });
    retry.flush({ ...payment, status: 'VOIDED', voidReason: 'Duplicado' });
    expect(component['voidTarget']()).toBeNull();
  });
  it('does not offer or accept an attendant void', () => {
    TestBed.overrideProvider(SessionService, {
      useValue: { session: () => ({ role: 'ATTENDANT' }) },
    });
    const { fixture, component, http, payment } = setupPayments();
    expect(fixture.nativeElement.querySelector('.void-button')).toBeNull();
    component['beginVoid'](payment);
    component['voidReason'] = 'Duplicado';
    component['confirmVoid']();
    expect(component['voidTarget']()).toBeNull();
    http.expectNone('/api/v1/payments/payment/void');
  });
});
