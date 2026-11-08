import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { WorkOrderPaymentsPanel } from './work-order-payments';

describe('Work order payments', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [WorkOrderPaymentsPanel],
      providers: [provideHttpClient(), provideHttpClientTesting()],
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
});
