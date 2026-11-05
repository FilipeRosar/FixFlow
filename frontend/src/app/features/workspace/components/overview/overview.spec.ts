import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { SessionService } from '../../../../core/auth/data-access/session.service';
import { Overview } from './overview';

describe('Overview', () => {
  const session = signal({ role: 'OWNER' });
  beforeEach(() => {
    session.set({ role: 'OWNER' });
    TestBed.configureTestingModule({
      imports: [Overview],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: SessionService, useValue: { session } },
      ],
    });
  });
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  function setup() {
    const fixture = TestBed.createComponent(Overview);
    fixture.detectChanges();
    return {
      fixture,
      http: TestBed.inject(HttpTestingController),
      page: fixture.nativeElement as HTMLElement,
    };
  }
  it('uses full status totals rather than the five recent orders to calculate indicators', () => {
    const { fixture, http, page } = setup();
    for (const request of http.match((r) => r.url === '/api/v1/work-orders')) {
      const status = request.request.params.get('status');
      request.flush({
        totalElements: status === 'EM_MANUTENCAO' ? 125 : status === 'PRONTA' ? 3 : 0,
        content: status
          ? []
          : [
              {
                id: 'order',
                number: 42,
                customerName: 'Ana',
                equipmentLabel: 'Notebook',
                status: 'PRONTA',
                openedAt: '2026-11-05T12:00:00Z',
              },
            ],
      });
    }
    fixture.detectChanges();
    expect(page.querySelector('.metric strong')?.textContent).toBe('128');
    expect(page.querySelector('tbody')?.textContent).toContain('Ana');
    expect(page.querySelector('tbody a')?.getAttribute('href')).toBe('/inicio/ordens/order');
  });
  it('reports a failed request and allows retry without presenting zero totals', () => {
    const { fixture, http, page } = setup();
    const requests = http.match((r) => r.url === '/api/v1/work-orders');
    requests.at(-1)!.flush({}, { status: 500, statusText: 'Error' });
    fixture.detectChanges();
    expect(page.querySelector('[role="alert"]')).not.toBeNull();
    expect(page.querySelector('.metrics')).toBeNull();
    page.querySelector<HTMLButtonElement>('button')!.click();
    for (const request of http.match((r) => r.url === '/api/v1/work-orders'))
      request.flush({ totalElements: 0, content: [] });
    fixture.detectChanges();
    expect(page.querySelector('[role="alert"]')).toBeNull();
    expect(page.textContent).toContain('Nenhuma ordem');
  });
  it('explains the assigned scope for technicians and omits financial and creation actions', () => {
    session.set({ role: 'TECHNICIAN' });
    const { fixture, http, page } = setup();
    for (const request of http.match((r) => r.url === '/api/v1/work-orders'))
      request.flush({ totalElements: 0, content: [] });
    fixture.detectChanges();
    expect(page.textContent).toContain('atribuídas a você');
    expect(page.textContent).not.toContain('Receita do mês');
    expect(page.querySelector('a[href="/inicio/ordens/nova"]')).toBeNull();
  });
});
