import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, provideRouter, Router } from '@angular/router';
import { ConfirmEmail } from './confirm-email';
import { sessionInterceptor } from '../../../../core/auth/interceptors/session.interceptor';
describe('Email confirmation', () => {
  const token = 'a'.repeat(43);
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [ConfirmEmail],
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([sessionInterceptor])),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { fragment: `token=${token}` } } },
      ],
    }),
  );
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  function setup() {
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const fixture = TestBed.createComponent(ConfirmEmail);
    fixture.detectChanges();
    return {
      fixture,
      page: fixture.nativeElement as HTMLElement,
      http: TestBed.inject(HttpTestingController),
      navigate,
    };
  }
  it('requires an explicit confirmation, removes the token from the URL and never logs in automatically', () => {
    const { fixture, page, http, navigate } = setup();
    http.expectNone('/api/contas/confirmar');
    expect(navigate).toHaveBeenCalled();
    page.querySelector('button')!.click();
    const request = http.expectOne('/api/contas/confirmar');
    expect(request.request.body).toEqual({ token });
    request.flush({ tenantId: 't', email: 'owner@example.com', role: 'OWNER' });
    fixture.detectChanges();
    expect(page.textContent).toContain('E-mail confirmado');
    expect(page.querySelector('a.button')?.getAttribute('href')).toBe('/entrar');
    http.expectNone('/api/v1/auth/refresh');
  });
  it('shows an expired-link error with a path to request another link', () => {
    const { fixture, page, http } = setup();
    page.querySelector('button')!.click();
    http
      .expectOne('/api/contas/confirmar')
      .flush({ detail: 'Link expirado.' }, { status: 410, statusText: 'Gone' });
    fixture.detectChanges();
    expect(page.querySelector('[role="alert"]')?.textContent).toContain('expirado');
    expect(page.querySelector('a[href="/cadastro"]')).not.toBeNull();
  });
});
