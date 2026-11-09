import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { WorkOrderPixPanel } from './work-order-pix';

describe('Asaas PIX charges', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [WorkOrderPixPanel],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }),
  );
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  function setup() {
    const fixture = TestBed.createComponent(WorkOrderPixPanel);
    fixture.componentRef.setInput('orderId', 'order');
    fixture.detectChanges();
    return {
      fixture,
      component: fixture.componentInstance,
      http: TestBed.inject(HttpTestingController),
    };
  }
  it('keeps the complete intent and idempotency key when creation is uncertain', () => {
    const { component, http } = setup();
    component['amount'] = 100;
    component['customer'] = 'cus_test';
    component['dueDate'] = '2030-12-01';
    component['create']();
    const first = http.expectOne('/api/v1/work-orders/order/pix-charges');
    const body = first.request.body;
    const key = first.request.headers.get('Idempotency-Key');
    first.flush({}, { status: 502, statusText: 'Bad Gateway' });
    component['amount'] = 200;
    component['create']();
    const retry = http.expectOne('/api/v1/work-orders/order/pix-charges');
    expect(retry.request.body).toEqual(body);
    expect(retry.request.headers.get('Idempotency-Key')).toBe(key);
    retry.flush({
      id: 'charge',
      amount: 100,
      dueDate: '2030-12-01',
      status: 'PENDING',
      payload: '000201PIX',
      encodedImage: 'aW1hZ2U=',
      expirationDate: null,
    });
    expect(component['pending']).toBeNull();
    expect(component['charges']()[0].status).toBe('PENDING');
    expect(component['notice']()).toContain('após o recebimento');
  });
  it('recovers a persisted intent through its refresh endpoint and renders the QR code', () => {
    const { fixture, component, http } = setup();
    component['toggle']();
    const charge = {
      id: 'charge',
      amount: 100,
      dueDate: '2030-12-01',
      status: 'CREATING',
      payload: null,
      encodedImage: null,
      expirationDate: null,
    };
    http.expectOne('/api/v1/work-orders/order/pix-charges').flush([charge]);
    component['refresh'](charge);
    http
      .expectOne('/api/v1/work-orders/order/pix-charges/charge/refresh')
      .flush({ ...charge, status: 'PENDING', payload: '000201PIX', encodedImage: 'aW1hZ2U=' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.pix-qr').getAttribute('src')).toBe(
      'data:image/png;base64,aW1hZ2U=',
    );
    expect(fixture.nativeElement.textContent).toContain('Aguardando pagamento');
  });
});
