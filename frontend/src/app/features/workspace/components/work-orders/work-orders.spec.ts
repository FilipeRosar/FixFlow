import { vi } from 'vitest';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { WorkOrdersPanel } from './work-orders';
import { SessionService } from '../../../../core/auth/data-access/session.service';
describe('Work order opening', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [WorkOrdersPanel],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: SessionService, useValue: { session: signal({ role: 'OWNER' }) } },
      ],
    }),
  );
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  it('loads customers as soon as the panel opens', () => {
    const fixture = TestBed.createComponent(WorkOrdersPanel);
    fixture.detectChanges();
    const request = TestBed.inject(HttpTestingController).expectOne(
      (r) => r.url === '/api/v1/customers' && r.params.get('q') === '',
    );
    request.flush({ content: [{ id: 'ana', name: 'Ana' }], totalElements: 1 });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Ana');
    expect(fixture.componentInstance['loading']()).toBe(false);
  });
  it('debounces typing and cancels the previous request instead of dropping the new query', () => {
    vi.useFakeTimers();
    try {
      const fixture = TestBed.createComponent(WorkOrdersPanel);
      fixture.detectChanges();
      const component = fixture.componentInstance;
      const http = TestBed.inject(HttpTestingController);
      const initial = http.expectOne((r) => r.url === '/api/v1/customers');
      component['query'] = 'An';
      component['customerQueryChanged']();
      expect(initial.cancelled).toBe(true);
      vi.advanceTimersByTime(200);
      http.expectNone((r) => r.url === '/api/v1/customers');
      component['query'] = 'Ana';
      component['customerQueryChanged']();
      vi.advanceTimersByTime(250);
      const previous = http.expectOne((r) => r.params.get('q') === 'Ana');
      component['query'] = 'Bruno';
      component['customerQueryChanged']();
      expect(previous.cancelled).toBe(true);
      vi.advanceTimersByTime(250);
      http
        .expectOne((r) => r.params.get('q') === 'Bruno' && r.params.get('page') === '0')
        .flush({ content: [{ id: 'bruno', name: 'Bruno' }], totalElements: 1 });
      expect(component['customers']()[0].name).toBe('Bruno');
      expect(component['loading']()).toBe(false);
      component['query'] = '';
      component['customerQueryChanged']();
      vi.advanceTimersByTime(250);
      http.expectOne((r) => r.params.get('q') === '').flush({ content: [], totalElements: 0 });
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Nenhum cliente encontrado');
      fixture.destroy();
    } finally {
      vi.useRealTimers();
    }
  });
  it('clears the equipment selection when the customer changes and ignores stale results', () => {
    const component = TestBed.createComponent(WorkOrdersPanel).componentInstance;
    const http = TestBed.inject(HttpTestingController);
    component['customerId'] = 'first';
    component['equipmentId'] = 'old';
    component['selectCustomer']();
    expect(component['equipmentId']).toBe('');
    const first = http.expectOne((r) => r.url.includes('/first/equipment'));
    component['customerId'] = 'second';
    component['selectCustomer']();
    http
      .expectOne((r) => r.url.includes('/second/equipment'))
      .flush({ content: [{ id: 'second-device' }], totalElements: 1 });
    first.flush({ content: [{ id: 'stale' }], totalElements: 1 });
    expect(component['equipment']()[0].id).toBe('second-device');
  });
  it('submits reception details once and shows the assigned number', () => {
    const fixture = TestBed.createComponent(WorkOrdersPanel);
    const component = fixture.componentInstance;
    component['customerId'] = 'customer';
    component['equipmentId'] = 'equipment';
    component['issue'] = ' Não liga ';
    component['condition'] = ' Risco na tampa ';
    component['accessories'] = 'Carregador\n\n Capa';
    component['open']();
    component['open']();
    const request = TestBed.inject(HttpTestingController).expectOne('/api/v1/work-orders');
    expect(request.request.body).toEqual({
      customerId: 'customer',
      equipmentId: 'equipment',
      reportedIssue: 'Não liga',
      physicalCondition: 'Risco na tampa',
      receivedAccessories: ['Carregador', 'Capa'],
    });
    request.flush({ number: 42, status: 'RECEBIDA' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('OS nº 42 aberta');
  });
  it('preserves entered data on a quota error', () => {
    const component = TestBed.createComponent(WorkOrdersPanel).componentInstance;
    component['customerId'] = 'customer';
    component['equipmentId'] = 'equipment';
    component['issue'] = 'Não liga';
    component['condition'] = 'Risco';
    component['open']();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/work-orders')
      .flush({ code: 'PLAN_LIMIT_REACHED' }, { status: 409, statusText: 'Conflict' });
    expect(component['issue']).toBe('Não liga');
    expect(component['error']()).toContain('Limite de OS');
    expect(component['saving']()).toBe(false);
  });
});
