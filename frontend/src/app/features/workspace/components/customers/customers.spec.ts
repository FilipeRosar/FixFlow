import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { CustomersPanel } from './customers';
import { SessionService } from '../../../../core/auth/data-access/session.service';

const customer = {
  id: 'client',
  name: 'Ana Silva',
  document: '12345678900',
  contacts: [{ type: 'PHONE', value: '11987654321', label: 'Trabalho' }],
};
describe('Customers', () => {
  let role = 'OWNER';
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [CustomersPanel],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: SessionService, useValue: { session: signal({ role }).asReadonly() } },
      ],
    }),
  );
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    role = 'OWNER';
  });
  function setup() {
    const fixture = TestBed.createComponent(CustomersPanel);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http
      .expectOne((r) => r.url === '/api/v1/customers')
      .flush({ content: [customer], totalElements: 21, page: 0, size: 20 });
    fixture.detectChanges();
    return {
      fixture,
      http,
      component: fixture.componentInstance,
      page: fixture.nativeElement as HTMLElement,
    };
  }
  it('searches and paginates using the applied query', () => {
    const { component, http } = setup();
    component['query'] = '  Ana  ';
    component['search']();
    http
      .expectOne((r) => r.params.get('q') === 'Ana' && r.params.get('page') === '0')
      .flush({ content: [customer], totalElements: 21, page: 0, size: 20 });
    component['query'] = 'unsubmitted';
    component['load'](1);
    http
      .expectOne((r) => r.params.get('q') === 'Ana' && r.params.get('page') === '1')
      .flush({ content: [], totalElements: 21, page: 1, size: 20 });
  });
  it('saves multiple contacts and does not mutate the list while editing', () => {
    const { component, http } = setup();
    component['edit'](customer);
    component['contacts'][0].value = '21999998888';
    expect(customer.contacts[0].value).toBe('11987654321');
    component['addContact']();
    component['contacts'][1] = { type: 'EMAIL', value: 'ana@example.com', label: '' };
    component['save']();
    const request = http.expectOne('/api/v1/customers/client');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body.contacts.length).toBe(2);
    request.flush(customer);
    http
      .expectOne((r) => r.url === '/api/v1/customers')
      .flush({ content: [customer], totalElements: 1, page: 0, size: 20 });
    expect(component['editing']()).toBe(false);
  });
  it('shows the open OS deletion conflict while preserving the customer', () => {
    const { component, http, fixture, page } = setup();
    component['deleting'].set(customer);
    component['remove']();
    http
      .expectOne('/api/v1/customers/client')
      .flush({ detail: 'Cliente possui OS aberta.' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(page.querySelector('[role="alert"]')?.textContent).toContain('OS aberta');
    expect(component['data']().content).toHaveLength(1);
  });
  it('keeps read access and hides mutations for technicians', () => {
    role = 'TECHNICIAN';
    // Reconfigure the provider before the component is created.
    TestBed.overrideProvider(SessionService, {
      useValue: { session: signal({ role }).asReadonly() },
    });
    const { page } = setup();
    expect(page.textContent).toContain('Ana Silva');
    expect(page.textContent).not.toContain('Novo cliente');
    expect(page.textContent).not.toContain('Excluir');
  });
});
