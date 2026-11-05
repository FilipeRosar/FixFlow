import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of, Subject } from 'rxjs';
import { WorkOrderDetail } from './work-order-detail';
const order = {
  id: 'order',
  number: 42,
  customerId: 'customer',
  equipmentId: 'equipment',
  customerName: 'Ana',
  equipmentLabel: 'Dell Inspiron',
  serialNumber: 'ABC',
  status: 'RECEBIDA',
  reportedIssue: 'Não liga',
  physicalCondition: 'Risco na tampa',
  receivedAccessories: ['Carregador'],
  openedAt: '2026-11-05T12:00:00Z',
  version: 0,
};
describe('Work order details', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [WorkOrderDetail],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { paramMap: of(convertToParamMap({ orderId: 'order' })) },
        },
      ],
    }),
  );
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  it('loads and displays the selected order with a link back to its customer history', () => {
    const fixture = TestBed.createComponent(WorkOrderDetail);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController).expectOne('/api/v1/work-orders/order').flush(order);
    fixture.detectChanges();
    const page = fixture.nativeElement as HTMLElement;
    for (const value of [
      '42',
      'Ana',
      'Dell Inspiron',
      'ABC',
      'Recebida',
      'Não liga',
      'Risco na tampa',
      'Carregador',
    ])
      expect(page.textContent).toContain(value);
    expect(page.querySelector('a')?.getAttribute('href')).toBe(
      '/inicio/clientes/customer/historico',
    );
  });
  it('handles an unavailable order and allows retrying', () => {
    const fixture = TestBed.createComponent(WorkOrderDetail);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/work-orders/order').flush({}, { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('não encontrada');
    fixture.nativeElement.querySelector('button').click();
    http
      .expectOne('/api/v1/work-orders/order')
      .flush({
        ...order,
        equipmentLabel: '',
        reportedIssue: null,
        physicalCondition: null,
        receivedAccessories: [],
      });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Não informado');
    expect(fixture.nativeElement.textContent).toContain('Nenhum acessório registrado');
  });
  it('cancels a previous request when navigating to another order', () => {
    const params = new Subject<ReturnType<typeof convertToParamMap>>();
    TestBed.overrideProvider(ActivatedRoute, { useValue: { paramMap: params } });
    const fixture = TestBed.createComponent(WorkOrderDetail);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    params.next(convertToParamMap({ orderId: 'first' }));
    const first = http.expectOne('/api/v1/work-orders/first');
    params.next(convertToParamMap({ orderId: 'second' }));
    expect(first.cancelled).toBe(true);
    http.expectOne('/api/v1/work-orders/second').flush({ ...order, number: 43 });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('43');
  });
});
