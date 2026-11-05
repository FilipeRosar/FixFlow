import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { SessionService } from '../../../../core/auth/data-access/session.service';
import { WorkOrderItemsPanel } from './work-order-items';

describe('Work order services and parts', () => {
  const session = signal({ role: 'OWNER' });
  beforeEach(() => {
    session.set({ role: 'OWNER' });
    TestBed.configureTestingModule({
      imports: [WorkOrderItemsPanel],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: SessionService, useValue: { session } },
      ],
    });
  });
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  function setup(status = 'EM_MANUTENCAO') {
    const fixture = TestBed.createComponent(WorkOrderItemsPanel);
    fixture.componentRef.setInput('order', { id: 'order', version: 0, status });
    fixture.detectChanges();
    fixture.componentInstance['toggle']();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/work-orders/order/items').flush({ items: [], workOrderVersion: 0 });
    if (status === 'EM_MANUTENCAO' && session().role !== 'ATTENDANT')
      http
        .expectOne('/api/v1/parts')
        .flush([{ id: 'part', name: 'Tela', sku: 'T1', quantityOnHand: 2, salePrice: 50 }]);
    fixture.detectChanges();
    return {
      fixture,
      http,
      component: fixture.componentInstance,
      page: fixture.nativeElement as HTMLElement,
    };
  }
  it('retains the item ID on an uncertain retry so stock cannot be consumed twice', () => {
    const { component, http } = setup();
    component['type'] = 'PART';
    component['partId'] = 'part';
    component['selectPart']();
    component['add']();
    const first = http.expectOne('/api/v1/work-orders/order/items');
    const id = first.request.body.id;
    expect(first.request.body.unitPrice).toBe(50);
    first.flush({}, { status: 500, statusText: 'Error' });
    component['add']();
    const retry = http.expectOne('/api/v1/work-orders/order/items');
    expect(retry.request.body.id).toBe(id);
    retry.flush({
      items: [{ id, type: 'PART', description: 'Tela', quantity: 1, unitPrice: 50 }],
      workOrderVersion: 1,
    });
    http.expectOne('/api/v1/parts').flush([]);
    expect(component['total']()).toBe(50);
  });
  it('removes a part and explains the stock return using the current order version', () => {
    const { fixture, component, http, page } = setup();
    const item = {
      id: 'item',
      type: 'PART',
      partId: 'part',
      description: 'Tela',
      quantity: 1,
      unitPrice: 50,
      unitCost: 20,
    };
    component['items'].set([item]);
    fixture.componentRef.setInput('order', { id: 'order', status: 'EM_MANUTENCAO', version: 7 });
    fixture.detectChanges();
    component['remove'](item);
    http
      .expectOne((r) => r.method === 'DELETE' && r.params.get('version') === '7')
      .flush({ items: [], workOrderVersion: 8 });
    http.expectOne('/api/v1/parts').flush([]);
    fixture.detectChanges();
    expect(page.textContent).toContain('devolvida ao estoque');
  });
  it('keeps attendants and completed orders read-only', () => {
    session.set({ role: 'ATTENDANT' });
    const { page, component } = setup('PRONTA');
    expect(page.querySelector('form')).toBeNull();
    component['description'] = 'Serviço';
    component['add']();
    expect(component['busy']()).toBe(false);
  });
  it('reports insufficient stock without changing displayed items', () => {
    const { component, http, fixture, page } = setup();
    component['type'] = 'PART';
    component['partId'] = 'part';
    component['selectPart']();
    component['add']();
    http
      .expectOne('/api/v1/work-orders/order/items')
      .flush(
        { code: 'INSUFFICIENT_STOCK', detail: 'Estoque insuficiente.' },
        { status: 422, statusText: 'Unprocessable' },
      );
    fixture.detectChanges();
    expect(page.querySelector('[role="alert"]')?.textContent).toContain('Estoque insuficiente');
    expect(component['items']()).toHaveLength(0);
  });
});
