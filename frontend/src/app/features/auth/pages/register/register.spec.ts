import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { Register } from './register';
import { sessionInterceptor } from '../../../../core/auth/interceptors/session.interceptor';
describe('Verified owner signup', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [Register],
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([sessionInterceptor])),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap({ plano: 'PROFESSIONAL' }) } },
        },
      ],
    }),
  );
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  function setup() {
    const fixture = TestBed.createComponent(Register);
    fixture.detectChanges();
    return {
      fixture,
      page: fixture.nativeElement as HTMLElement,
      http: TestBed.inject(HttpTestingController),
    };
  }
  function fill(page: HTMLElement, id: string, value: string) {
    const input = page.querySelector<HTMLInputElement>(`#${id}`)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }
  function populate(page: HTMLElement) {
    fill(page, 'firstName', 'Owner');
    fill(page, 'lastName', 'Test');
    for (const [field, value] of Object.entries({
      document: '12345678901',
      phone: '11999999999',
      postalCode: '01001000',
      street: 'Praça da Sé',
      number: '10',
      neighborhood: 'Sé',
      city: 'São Paulo',
      state: 'SP',
      confirmPassword: 'Owner1234!',
    }))
      fill(page, field, value);
    fill(page, 'companyName', 'Oficina');
    fill(page, 'email', 'owner@example.com');
    fill(page, 'password', 'Owner1234!');
  }
  it('preserves the paid Professional plan selected in the URL', () => {
    const { fixture, page, http } = setup();
    populate(page);
    page.querySelector('form')!.dispatchEvent(new Event('submit'));
    const request = http.expectOne('/api/contas');
    expect(request.request.body).toEqual({
      fullName: 'Owner Test',
      document: '12345678901',
      phone: '11999999999',
      address: {
        street: 'Praça da Sé',
        number: '10',
        complement: '',
        neighborhood: 'Sé',
        city: 'São Paulo',
        state: 'SP',
        postalCode: '01001000',
      },
      companyName: 'Oficina',
      email: 'owner@example.com',
      password: 'Owner1234!',
      plan: 'PROFESSIONAL',
    });
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush({ message: 'Confirme seu e-mail' }, { status: 202, statusText: 'Accepted' });
    fixture.detectChanges();
    expect(page.textContent).toContain('Confira seu e-mail');
    expect(page.querySelector('form')).toBeNull();
    expect(fixture.componentInstance['form'].controls.password.value).toBe('');
    http.expectNone('/api/v1/auth/refresh');
  });
  it('defaults to a free Starter trial without a paid plan in the URL', () => {
    TestBed.overrideProvider(ActivatedRoute, { useValue: { snapshot: { queryParamMap: convertToParamMap({}) } } });
    const { page, http } = setup();
    populate(page);
    expect(page.textContent).toContain('Sem cobrança automática');
    page.querySelector('form')!.dispatchEvent(new Event('submit'));
    const request = http.expectOne('/api/contas');
    expect(request.request.body.plan).toBe('STARTER');
    request.flush({}, { status: 202, statusText: 'Accepted' });
  });
  it('validates the password and includes document and address at signup', () => {
    const { fixture, page, http } = setup();
    populate(page);
    fill(page, 'password', 'weak');
    page.querySelector('form')!.dispatchEvent(new Event('submit'));
    fixture.detectChanges();
    http.expectNone('/api/contas');
    expect(page.querySelector('#document')).not.toBeNull();
    expect(page.querySelector('#postalCode')).not.toBeNull();
    expect(page.querySelector('.field-error')).not.toBeNull();
  });
  it('requires profile fields and matching passwords', () => {
    const { page, http } = setup();
    populate(page);
    for (const field of ['document', 'phone', 'street', 'confirmPassword']) {
      const previous = page.querySelector<HTMLInputElement>(`#${field}`)!.value;
      fill(page, field, field === 'confirmPassword' ? 'Different123' : '');
      page.querySelector('form')!.dispatchEvent(new Event('submit'));
      http.expectNone('/api/contas');
      fill(page, field, previous);
    }
  });
  it('keeps the form available if confirmation mail could not be sent', () => {
    const { fixture, page, http } = setup();
    populate(page);
    page.querySelector('form')!.dispatchEvent(new Event('submit'));
    http
      .expectOne('/api/contas')
      .flush(
        { detail: 'Não foi possível enviar a confirmação.' },
        { status: 503, statusText: 'Unavailable' },
      );
    fixture.detectChanges();
    expect(page.querySelector('[role="alert"]')?.textContent).toContain('enviar a confirmação');
    expect(page.querySelector('form')).not.toBeNull();
  });
});
