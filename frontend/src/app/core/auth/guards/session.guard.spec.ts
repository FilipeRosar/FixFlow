import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { routes } from '../../../app.routes';

describe('Protected workspace', () => {
  it('restores the session on reload before allowing navigation', async () => {
    TestBed.configureTestingModule({
      providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()],
    });
    const harness = await RouterTestingHarness.create();
    const navigation = harness.navigateByUrl('/inicio');
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    TestBed.inject(HttpTestingController).expectOne('/api/v1/auth/refresh').flush({
      accessToken: 'jwt',
      expiresIn: 900,
      userId: 'u',
      tenantId: 't',
      role: 'OWNER',
    });
    await navigation;
    TestBed.inject(HttpTestingController)
      .expectOne('/api/oficinas/t/selecao')
      .flush({ userId: 'u', tenantId: 't', role: 'OWNER', companyName: 'Oficina Horizonte' });
    harness.detectChanges();
    expect(harness.routeNativeElement?.querySelector('.company strong')?.textContent).toContain(
      'Oficina Horizonte',
    );
    expect(TestBed.inject(Router).url).toBe('/inicio');
    expect(
      harness.routeNativeElement?.querySelector('[aria-current="page"]')?.textContent,
    ).toContain('Visão geral');
    TestBed.inject(HttpTestingController).verify();
  });
  it('opens settings as its own page and returns to the overview', async () => {
    TestBed.configureTestingModule({
      providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()],
    });
    const http = TestBed.inject(HttpTestingController);
    const harness = await RouterTestingHarness.create();
    const navigation = harness.navigateByUrl('/inicio/configuracoes');
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    http
      .expectOne('/api/v1/auth/refresh')
      .flush({ accessToken: 'jwt', expiresIn: 900, userId: 'u', tenantId: 't', role: 'OWNER' });
    await navigation;
    http
      .expectOne('/api/oficinas/t/selecao')
      .flush({ userId: 'u', tenantId: 't', role: 'OWNER', companyName: 'Oficina Horizonte' });
    http
      .expectOne('/api/v1/tenant')
      .flush({
        logoDataUrl: null,
        warrantyTerms: '',
        quoteValidityDays: 7,
        allowNegativeStock: false,
      });
    harness.detectChanges();
    expect(
      harness.routeNativeElement?.querySelector('[aria-current="page"]')?.textContent,
    ).toContain('Configurações');
    expect(harness.routeNativeElement?.querySelector('h1')?.textContent).toBe('Configurações');
    expect(harness.routeNativeElement?.querySelector('app-company-settings')).not.toBeNull();
    expect(harness.routeNativeElement?.querySelector('.metrics')).toBeNull();
    expect(harness.routeNativeElement?.querySelector('.user-management')).toBeNull();
    await harness.navigateByUrl('/inicio');
    http
      .expectOne('/api/oficinas/t/selecao')
      .flush({ userId: 'u', tenantId: 't', role: 'OWNER', companyName: 'Oficina Horizonte' });
    harness.detectChanges();
    expect(
      harness.routeNativeElement?.querySelector('[aria-current="page"]')?.textContent,
    ).toContain('Visão geral');
    expect(harness.routeNativeElement?.querySelector('app-company-settings')).toBeNull();
    expect(harness.routeNativeElement?.querySelector('.metrics')).not.toBeNull();
    http.verify();
  });
  it('redirects staff away from the settings page', async () => {
    TestBed.configureTestingModule({
      providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()],
    });
    const http = TestBed.inject(HttpTestingController);
    const harness = await RouterTestingHarness.create();
    const navigation = harness.navigateByUrl('/inicio/configuracoes');
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    http
      .expectOne('/api/v1/auth/refresh')
      .flush({ accessToken: 'jwt', expiresIn: 900, userId: 'u', tenantId: 't', role: 'ATTENDANT' });
    await navigation;
    http
      .expectOne('/api/oficinas/t/selecao')
      .flush({ userId: 'u', tenantId: 't', role: 'ATTENDANT', companyName: 'Oficina Horizonte' });
    expect(TestBed.inject(Router).url).toBe('/inicio');
    expect(harness.routeNativeElement?.querySelector('app-company-settings')).toBeNull();
    http.expectNone('/api/v1/tenant');
    http.verify();
  });
});
