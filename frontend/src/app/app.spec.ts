import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { App } from './app';
import { routes } from './app.routes';
import { SessionService } from './core/auth/data-access/session.service';

describe('FixFlow navigation', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
  });

  it('renders the FixFlow home through the router', async () => {
    const fixture = TestBed.createComponent(App);
    await TestBed.inject(Router).navigateByUrl('/');
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    expect(page.querySelector('h1')?.textContent).toContain('Seu talento é reparar.');
    expect(page.querySelector('nav')?.getAttribute('aria-label')).toBe('Navegação principal');
    expect(page.querySelector('.release-note')?.textContent).toContain('Em desenvolvimento');
  });

  it('uses the operational layout after login and restores the public header on return', async () => {
    const fixture = TestBed.createComponent(App);
    TestBed.inject(SessionService)
      .login({ email: 'owner@example.com', password: 'Owner1234!' })
      .subscribe();
    TestBed.inject(HttpTestingController).expectOne('/api/v1/auth/login').flush({
      accessToken: 'token',
      tokenType: 'Bearer',
      expiresIn: 900,
      userId: 'u',
      tenantId: 't',
      role: 'OWNER',
    });
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/inicio');
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    TestBed.inject(HttpTestingController)
      .expectOne('/api/oficinas/t/selecao')
      .flush({ userId: 'u', tenantId: 't', role: 'OWNER', companyName: 'Oficina Horizonte' });
    expect(page.querySelector('app-header')).toBeNull();
    expect(page.querySelector('.operation-nav')).not.toBeNull();
    expect(page.querySelectorAll('footer')).toHaveLength(1);
    for (const request of TestBed.inject(HttpTestingController).match(
      (r) => r.url === '/api/v1/work-orders',
    )) {
      request.flush({ content: [], totalElements: 0 });
    }
    await router.navigateByUrl('/');
    await fixture.whenStable();
    expect(page.querySelector('app-header')).not.toBeNull();
    expect(page.querySelector('.operation-nav')).toBeNull();
    TestBed.inject(HttpTestingController).verify();
  });

  it('redirects an unknown route to the home', async () => {
    TestBed.createComponent(App);
    await TestBed.inject(Router).navigateByUrl('/pagina-inexistente');
    expect(TestBed.inject(Router).url).toBe('/');
  });
});
