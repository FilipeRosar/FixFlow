import { vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { of } from 'rxjs';
import { routes } from '../../../../app.routes';
import { SessionService } from '../../../../core/auth/data-access/session.service';
describe('First visit after email verification', () => {
  const session = {
    role: 'OWNER',
    tenantId: 't',
    userId: 'u',
    accessToken: 'token',
    expiresIn: 900,
    tokenType: 'Bearer',
  };
  beforeEach(() =>
    TestBed.configureTestingModule({
      providers: [
        provideRouter(routes),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: SessionService,
          useValue: { session: signal(session), ensureSession: () => of(session) },
        },
      ],
    }),
  );
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  function flushAccess(http: HttpTestingController, writable: boolean) {
    http.expectOne('/api/v1/subscription/access').flush({
      status: writable ? 'TRIALING' : 'EXPIRED',
      writable,
      trialDaysRemaining: writable ? 14 : 0,
      accessUntil: '2026-11-23T00:00:00Z',
    });
  }
  async function navigate(harness: RouterTestingHarness, writable: boolean) {
    const navigation = harness.navigateByUrl('/inicio');
    await vi.waitFor(() => flushAccess(TestBed.inject(HttpTestingController), writable));
    await navigation;
  }
  function flushWorkspace(http: HttpTestingController, writable: boolean) {
    for (const req of http.match((r) => r.url === '/api/v1/work-orders'))
      req.flush({ content: [], totalElements: 0 });
    http
      .expectOne('/api/oficinas/t/selecao')
      .flush({ ...session, companyName: 'Oficina', onboardingRequired: true });
    flushAccess(http, writable);
  }
  it('opens guided setup when a verified owner has an active trial', async () => {
    const harness = await RouterTestingHarness.create();
    await navigate(harness, true);
    const http = TestBed.inject(HttpTestingController);
    flushWorkspace(http, true);
    await vi.waitFor(() => flushAccess(http, true));
    await vi.waitFor(() => flushWorkspace(http, true));
    expect(TestBed.inject(Router).url).toBe('/inicio/configuracao-inicial');
    http.expectOne('/api/v1/onboarding').flush({
      profileCompleted: false,
      completed: false,
      profile: { companyName: 'Oficina', address: {} },
      customers: 0,
      equipment: 0,
      firstOrderId: null,
    });
    harness.detectChanges();
    expect(harness.routeNativeElement?.querySelector('app-onboarding')).not.toBeNull();
    expect(harness.routeNativeElement?.textContent).toContain('Dados da assistência');
  });
  it('preserves read access when a company never completed setup and its trial expired', async () => {
    const harness = await RouterTestingHarness.create();
    await navigate(harness, false);
    const http = TestBed.inject(HttpTestingController);
    flushWorkspace(http, false);
    await vi.waitFor(() => flushAccess(http, false));
    await vi.waitFor(() => flushWorkspace(http, false));
    http.expectOne('/api/v1/subscription').flush({
      access: {
        status: 'EXPIRED',
        writable: false,
        trialDaysRemaining: 0,
        accessUntil: '2026-11-23T00:00:00Z',
      },
      currentPlan: 'STARTER',
      paidUntil: null,
      cancelAtPeriodEnd: false,
      contract: null,
      plans: [],
      invoices: [],
      activeUsers: 1,
      ordersThisMonth: 0,
      gatewayAvailable: false,
    });
    harness.detectChanges();
    expect(TestBed.inject(Router).url).toBe('/inicio/configuracoes/assinatura');
    expect(
      harness.routeNativeElement?.querySelector('.subscription-banner')?.textContent,
    ).toContain('leitura');
    http.expectNone('/api/v1/onboarding');
  });
});
