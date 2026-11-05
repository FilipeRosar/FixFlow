import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { WorkOrderQueue } from './work-order-queue';
import { SessionService } from '../../../../core/auth/data-access/session.service';
const order = {
  id: 'order',
  number: 42,
  customerName: 'Ana',
  equipmentLabel: 'Dell Inspiron',
  technicianName: 'João',
  status: 'EM_DIAGNOSTICO',
  openedAt: '2026-11-05T12:00:00Z',
};
describe('Work order queue', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [WorkOrderQueue],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: SessionService, useValue: { session: signal({ role: 'TECHNICIAN' }) } },
      ],
    }),
  );
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  it('loads the technician queue without a client-selected technician and links to the order', () => {
    const fixture = TestBed.createComponent(WorkOrderQueue);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectNone('/api/v1/work-orders/technicians');
    const request = http.expectOne((r) => r.url === '/api/v1/work-orders');
    expect(request.request.params.has('technicianId')).toBe(false);
    expect(request.request.params.get('openOnly')).toBe('true');
    request.flush({ content: [order], totalElements: 1, page: 0, size: 20 });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Minha fila');
    expect(fixture.nativeElement.textContent).toContain('Em diagnóstico');
    expect(fixture.nativeElement.querySelector('select[name="technicianId"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('.order-link').getAttribute('href')).toBe(
      '/inicio/ordens/order',
    );
  });
  it('allows an owner to filter by technician and preserves filters across pages', () => {
    TestBed.overrideProvider(SessionService, { useValue: { session: signal({ role: 'OWNER' }) } });
    const fixture = TestBed.createComponent(WorkOrderQueue);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/work-orders/technicians').flush([{ userId: 'joao', fullName: 'João' }]);
    http
      .expectOne((r) => r.url === '/api/v1/work-orders')
      .flush({ content: [], totalElements: 0, page: 0, size: 20 });
    const component = fixture.componentInstance;
    component['technicianId'] = 'joao';
    component['status'] = 'EM_DIAGNOSTICO';
    component['filter']();
    http
      .expectOne(
        (r) =>
          r.params.get('technicianId') === 'joao' &&
          r.params.get('status') === 'EM_DIAGNOSTICO' &&
          r.params.get('page') === '0',
      )
      .flush({ content: [order], totalElements: 21, page: 0, size: 20 });
    component['load'](1);
    http
      .expectOne(
        (r) =>
          r.params.get('technicianId') === 'joao' &&
          r.params.get('status') === 'EM_DIAGNOSTICO' &&
          r.params.get('page') === '1',
      )
      .flush({ content: [order], totalElements: 21, page: 1, size: 20 });
    component['status'] = 'ENTREGUE';
    component['filter']();
    http
      .expectOne((r) => r.params.get('openOnly') === 'false' && r.params.get('page') === '0')
      .flush({ content: [], totalElements: 0, page: 0, size: 20 });
  });
  it('cancels stale requests and shows access errors instead of old orders', () => {
    const fixture = TestBed.createComponent(WorkOrderQueue);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    const first = http.expectOne((r) => r.url === '/api/v1/work-orders');
    const component = fixture.componentInstance;
    component['status'] = 'PRONTA';
    component['filter']();
    expect(first.cancelled).toBe(true);
    http
      .expectOne((r) => r.params.get('status') === 'PRONTA')
      .flush({ content: [order], totalElements: 1, page: 0, size: 20 });
    component['load'](0);
    http
      .expectOne((r) => r.url === '/api/v1/work-orders')
      .flush({}, { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Você não tem permissão');
    expect(fixture.nativeElement.querySelector('.order-link')).toBeNull();
    expect(fixture.nativeElement.textContent).not.toContain('Nenhuma OS encontrada');
  });
});
