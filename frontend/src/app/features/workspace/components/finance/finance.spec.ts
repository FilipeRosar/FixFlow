import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { FinancePanel } from './finance';

describe('FinancePanel', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [FinancePanel],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }),
  );
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  it('shows server totals independently of the paginated history and links the order', () => {
    const fixture = TestBed.createComponent(FinancePanel);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http
      .expectOne((r) => r.url === '/api/v1/finance')
      .flush({
        summary: { received: 900, voided: 50, confirmedCount: 30 },
        totalElements: 31,
        content: [
          {
            id: 'p1',
            workOrderId: 'order1',
            number: 42,
            customerName: 'Ana',
            amount: 50,
            method: 'PIX',
            status: 'VOIDED',
            paidAt: '2026-11-09T12:00:00Z',
          },
        ],
      });
    fixture.detectChanges();
    const page: HTMLElement = fixture.nativeElement;
    expect(page.querySelector('.metrics')?.textContent).toContain('900,00');
    expect(page.querySelector('tbody')?.textContent).toContain('Estornado');
    expect(page.querySelector('tbody a')?.getAttribute('href')).toBe('/inicio/ordens/order1');
    const next = Array.from(page.querySelectorAll('button')).find((b) =>
      b.textContent?.includes('Próxima'),
    )!;
    next.click();
    http
      .expectOne((r) => r.params.get('page') === '1')
      .flush({
        summary: { received: 900, voided: 50, confirmedCount: 30 },
        totalElements: 31,
        content: [],
      });
  });
  it('hides totals after an error and supports retry with an empty result', () => {
    const fixture = TestBed.createComponent(FinancePanel);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http
      .expectOne((r) => r.url === '/api/v1/finance')
      .flush({}, { status: 500, statusText: 'Error' });
    fixture.detectChanges();
    const page: HTMLElement = fixture.nativeElement;
    expect(page.querySelector('.metrics')).toBeNull();
    page.querySelector<HTMLButtonElement>('[role="alert"] button')!.click();
    http
      .expectOne((r) => r.url === '/api/v1/finance')
      .flush({
        summary: { received: 0, voided: 0, confirmedCount: 0 },
        totalElements: 0,
        content: [],
      });
    fixture.detectChanges();
    expect(page.querySelector('[role="alert"]')).toBeNull();
    expect(page.textContent).toContain('Nenhum pagamento encontrado');
  });
});
