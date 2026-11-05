import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, ActivatedRoute, convertToParamMap } from '@angular/router';
import { of } from 'rxjs';
import { EquipmentPanel } from './equipment';
import { SessionService } from '../../../../core/auth/data-access/session.service';
const customer = { id: 'customer', name: 'Ana Silva' };
const equipment = {
  id: 'equipment',
  customerId: customer.id,
  customerName: customer.name,
  customerDeleted: false,
  category: 'NOTEBOOK',
  brand: 'Dell',
  model: 'Inspiron',
  serialNumber: 'ABC-123',
  description: 'Prateado',
};
describe('Equipment', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [EquipmentPanel],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: SessionService, useValue: { session: signal({ role: 'OWNER' }).asReadonly() } },
        { provide: ActivatedRoute, useValue: { queryParamMap: of(convertToParamMap({})) } },
      ],
    }),
  );
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  function setup() {
    const fixture = TestBed.createComponent(EquipmentPanel);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http
      .expectOne((r) => r.url === '/api/v1/equipment')
      .flush({ content: [equipment], totalElements: 21, page: 0, size: 20 });
    fixture.detectChanges();
    return {
      fixture,
      http,
      component: fixture.componentInstance,
      page: fixture.nativeElement as HTMLElement,
    };
  }
  it('searches serial numbers and preserves the submitted query across pages', () => {
    const { component, http } = setup();
    component['serial'] = '  ABC  ';
    component['search']();
    http
      .expectOne((r) => r.params.get('serial') === 'ABC' && r.params.get('page') === '0')
      .flush({ content: [equipment], totalElements: 21, page: 0, size: 20 });
    component['serial'] = 'not submitted';
    component['load'](1);
    http
      .expectOne((r) => r.params.get('serial') === 'ABC' && r.params.get('page') === '1')
      .flush({ content: [], totalElements: 21, page: 1, size: 20 });
  });
  it('requires a customer and sends its ID when creating equipment', () => {
    const { component, http } = setup();
    component['edit']();
    http
      .expectOne((r) => r.url === '/api/v1/customers')
      .flush({ content: [customer], totalElements: 1 });
    component['save']();
    expect(component['error']()).toContain('Selecione o cliente');
    component['selectedCustomer'] = customer;
    component['model'] = 'Inspiron';
    component['save']();
    const request = http.expectOne('/api/v1/equipment');
    expect(request.request.method).toBe('POST');
    expect(request.request.body.customerId).toBe('customer');
    request.flush(equipment);
    http
      .expectOne((r) => r.url === '/api/v1/equipment')
      .flush({ content: [equipment], totalElements: 1, page: 0, size: 20 });
    expect(component['editing']()).toBe(false);
  });
  it('allows technicians to edit only the description', async () => {
    TestBed.overrideProvider(SessionService, {
      useValue: { session: signal({ role: 'TECHNICIAN' }).asReadonly() },
    });
    const { component, http, fixture, page } = setup();
    expect(page.textContent).not.toContain('Novo equipamento');
    expect(page.textContent).not.toContain('Excluir');
    component['edit'](equipment);
    fixture.detectChanges();
    await fixture.whenStable();
    expect((page.querySelector('input[name="model"]') as HTMLInputElement).disabled).toBe(true);
    component['description'] = 'Atualizada';
    component['save']();
    const request = http.expectOne('/api/v1/equipment/equipment/description');
    expect(request.request.method).toBe('PATCH');
    expect(request.request.body).toEqual({ description: 'Atualizada' });
    request.flush(equipment);
    http
      .expectOne((r) => r.url === '/api/v1/equipment')
      .flush({ content: [equipment], totalElements: 1, page: 0, size: 20 });
  });
  it('keeps the editor open when the selected customer is rejected', () => {
    const { component, http } = setup();
    component['edit'](equipment);
    http
      .expectOne((r) => r.url === '/api/v1/customers')
      .flush({ content: [customer], totalElements: 1 });
    component['save']();
    http
      .expectOne('/api/v1/equipment/equipment')
      .flush(
        { detail: 'Cliente não encontrado nesta empresa.' },
        { status: 404, statusText: 'Not Found' },
      );
    expect(component['editing']()).toBe(true);
    expect(component['error']()).toContain('Cliente não encontrado');
  });
  it('filters equipment when navigating from a customer', () => {
    TestBed.overrideProvider(ActivatedRoute, {
      useValue: { queryParamMap: of(convertToParamMap({ customerId: 'customer' })) },
    });
    const { component, http } = setup();
    component['load'](1);
    http
      .expectOne((r) => r.url === '/api/v1/equipment' && r.params.get('customerId') === 'customer')
      .flush({ content: [], totalElements: 0, page: 1, size: 20 });
  });
});
