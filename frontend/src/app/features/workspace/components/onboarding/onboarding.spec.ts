import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { signal } from '@angular/core';
import { OnboardingPanel } from './onboarding';
import { SessionService } from '../../../../core/auth/data-access/session.service';
const profile = {
  companyName: 'Oficina',
  document: '52998224725',
  phone: '11999999999',
  address: {
    street: 'Rua Teste',
    number: '123',
    complement: '',
    neighborhood: 'Centro',
    city: 'São Paulo',
    state: 'SP',
    postalCode: '01001000',
  },
};
const view = {
  profileCompleted: false,
  completed: false,
  profile,
  customers: 0,
  equipment: 0,
  firstOrderId: null,
};
describe('Guided setup', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [OnboardingPanel],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: SessionService, useValue: { session: signal({ role: 'OWNER' }) } },
      ],
    }),
  );
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  function setup(data: object = view) {
    const fixture = TestBed.createComponent(OnboardingPanel);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/onboarding').flush(data);
    fixture.detectChanges();
    return { fixture, http, page: fixture.nativeElement as HTMLElement };
  }
  it('saves company data and advances to the existing customer registration', async () => {
    const { fixture, page, http } = setup();
    await fixture.whenStable();
    page.querySelector('form')!.dispatchEvent(new Event('submit'));
    const request = http.expectOne('/api/v1/onboarding');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body.document).toBe('52998224725');
    request.flush({ ...view, profileCompleted: true });
    fixture.detectChanges();
    for (const req of http.match((r) => r.url === '/api/v1/customers'))
      req.flush({ content: [], totalElements: 0 });
    expect(page.querySelector('app-customers')).not.toBeNull();
  });
  it('does not advance until the server confirms a customer exists', () => {
    const { fixture, page, http } = setup({ ...view, profileCompleted: true });
    for (const req of http.match((r) => r.url === '/api/v1/customers'))
      req.flush({ content: [], totalElements: 0 });
    [...page.querySelectorAll('button')]
      .find((b) => b.textContent?.includes('Cliente cadastrado'))!
      .click();
    http.expectOne('/api/v1/onboarding').flush({ ...view, profileCompleted: true });
    fixture.detectChanges();
    expect(page.querySelector('[role="alert"]')?.textContent).toContain('Conclua o cadastro');
    expect(page.querySelector('app-equipment')).toBeNull();
  });
  it('finishes on the first actual work order after server confirmation', () => {
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const { fixture, page, http } = setup({
      ...view,
      profileCompleted: true,
      firstOrderId: 'os-1',
    });
    page.querySelector('.setup-card button')!.dispatchEvent(new Event('click'));
    http
      .expectOne('/api/v1/onboarding/complete')
      .flush({ ...view, completed: true, firstOrderId: 'os-1' });
    fixture.detectChanges();
    expect(navigate).toHaveBeenCalledWith(['/inicio/ordens', 'os-1']);
  });
});
