import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of, Subject } from 'rxjs';
import { CustomerHistoryPanel } from './customer-history';
const page = {
  customerId: 'customer',
  customerName: 'Ana Silva',
  totalElements: 21,
  page: 0,
  size: 20,
  sort: 'desc',
  workOrdersVisible: true,
  content: [
    {
      id: 'order',
      type: 'WORK_ORDER',
      createdAt: '2025-05-01T12:00:00Z',
      title: 'Ordem de serviço',
      status: 'ENTREGUE',
      serialNumber: null,
      deleted: false,
    },
    {
      id: 'equipment',
      type: 'EQUIPMENT',
      createdAt: '2025-04-01T12:00:00Z',
      title: 'Samsung Galaxy',
      status: null,
      serialNumber: 'ABC123',
      deleted: true,
    },
  ],
};
describe('Customer history', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [CustomerHistoryPanel],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { paramMap: of(convertToParamMap({ customerId: 'customer' })) },
        },
      ],
    }),
  );
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  function setup(response = page) {
    const fixture = TestBed.createComponent(CustomerHistoryPanel);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http
      .expectOne(
        (r) => r.url === '/api/v1/customers/customer/history' && r.params.get('sort') === 'desc',
      )
      .flush(response);
    fixture.detectChanges();
    return {
      fixture,
      http,
      component: fixture.componentInstance,
      element: fixture.nativeElement as HTMLElement,
    };
  }
  it('displays OS and equipment with dates, status and soft deletion indication', () => {
    const { element } = setup();
    expect(element.textContent).toContain('Ana Silva');
    expect(element.textContent).toContain('Entregue');
    expect(element.textContent).toContain('Samsung Galaxy');
    expect(element.textContent).toContain('ABC123');
    expect(element.textContent).toContain('Excluído');
    expect(element.querySelectorAll('time')).toHaveLength(2);
  });
  it('sends type, sorting and page size and preserves them while paging', () => {
    const { component, http } = setup();
    component['type'] = 'EQUIPMENT';
    component['sort'] = 'asc';
    component['size'] = 10;
    component['load'](0);
    http
      .expectOne(
        (r) =>
          r.params.get('type') === 'EQUIPMENT' &&
          r.params.get('sort') === 'asc' &&
          r.params.get('size') === '10' &&
          r.params.get('page') === '0',
      )
      .flush({ ...page, content: [], sort: 'asc', size: 10 });
    component['load'](1);
    http
      .expectOne(
        (r) =>
          r.params.get('page') === '1' &&
          r.params.get('type') === 'EQUIPMENT' &&
          r.params.get('sort') === 'asc',
      )
      .flush({ ...page, content: [], page: 1, size: 10 });
  });
  it('hides the OS filter when only equipment is authorized', () => {
    const { element } = setup({
      ...page,
      workOrdersVisible: false,
      totalElements: 1,
      content: [page.content[1]],
    });
    expect(element.querySelector('option[value="WORK_ORDER"]')).toBeNull();
    expect(element.textContent).toContain('Sua permissão permite consultar os equipamentos');
    expect(element.textContent).not.toContain('Entregue');
  });
  it('handles an unavailable customer without showing a misleading empty state', () => {
    const fixture = TestBed.createComponent(CustomerHistoryPanel);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController)
      .expectOne((r) => r.url.endsWith('/history'))
      .flush({}, { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('[role="alert"]')?.textContent).toContain(
      'Cliente não encontrado',
    );
    expect(element.textContent).not.toContain('Nenhum registro encontrado');
  });
  it('discards a late response from a previously selected customer', () => {
    const params = new Subject<ReturnType<typeof convertToParamMap>>();
    TestBed.overrideProvider(ActivatedRoute, { useValue: { paramMap: params.asObservable() } });
    const fixture = TestBed.createComponent(CustomerHistoryPanel);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    params.next(convertToParamMap({ customerId: 'first' }));
    const first = http.expectOne((r) => r.url === '/api/v1/customers/first/history');
    params.next(convertToParamMap({ customerId: 'second' }));
    http
      .expectOne((r) => r.url === '/api/v1/customers/second/history')
      .flush({ ...page, customerId: 'second', customerName: 'Bruno' });
    first.flush({ ...page, customerId: 'first' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Bruno');
    expect(fixture.nativeElement.textContent).not.toContain('Ana Silva');
  });
});
