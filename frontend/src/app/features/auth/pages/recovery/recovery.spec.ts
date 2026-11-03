import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { Location } from '@angular/common';
import { Recovery } from './recovery';
import { sessionInterceptor } from '../../../../core/auth/interceptors/session.interceptor';

const token = 'A'.repeat(43);
function setup(reset = false, fragment = '') {
  TestBed.configureTestingModule({
    imports: [Recovery],
    providers: [
      provideRouter([]),
      provideHttpClient(withInterceptors([sessionInterceptor])),
      provideHttpClientTesting(),
      { provide: ActivatedRoute, useValue: { snapshot: { data: { reset }, fragment } } },
    ],
  });
  const replace = vi.spyOn(TestBed.inject(Location), 'replaceState');
  const fixture = TestBed.createComponent(Recovery);
  fixture.detectChanges();
  return {
    fixture,
    page: fixture.nativeElement as HTMLElement,
    http: TestBed.inject(HttpTestingController),
    replace,
  };
}
function fill(page: HTMLElement, id: string, value: string) {
  const input = page.querySelector<HTMLInputElement>(`#${id}`)!;
  input.value = value;
  input.dispatchEvent(new Event('input'));
}
function submit(page: HTMLElement) {
  page
    .querySelector('form')!
    .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
}
afterEach(() => TestBed.inject(HttpTestingController).verify());
describe('Account recovery screens', () => {
  it('submits email without authentication and displays the generic message', () => {
    const { fixture, page, http } = setup();
    fill(page, 'email', ' owner@example.com ');
    submit(page);
    const request = http.expectOne('/api/v1/auth/forgot-password');
    expect(request.request.body).toEqual({ email: 'owner@example.com' });
    expect(request.request.headers.get('X-Auth-Request')).toBe('true');
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush({ message: 'generic' }, { status: 202, statusText: 'Accepted' });
    fixture.detectChanges();
    expect(page.querySelector('[role="status"]')?.textContent).toContain('Se houver uma conta');
  });
  it('rejects an invalid link without calling the API', () => {
    const { page, http } = setup(true, 'token=invalid');
    expect(page.querySelector('[role="alert"]')?.textContent).toContain('Link inválido');
    expect(page.querySelector('form')).toBeNull();
    http.expectNone('/api/v1/auth/reset-password');
  });
  it('removes the token from the URL, requires matching passwords and sends only the new password and token', () => {
    const { fixture, page, http, replace } = setup(true, `token=${token}`);
    expect(replace).toHaveBeenCalledWith('/redefinir-senha');
    fill(page, 'newPassword', 'Newpass1');
    fill(page, 'confirmPassword', 'Different1');
    submit(page);
    http.expectNone('/api/v1/auth/reset-password');
    fill(page, 'confirmPassword', 'Newpass1');
    submit(page);
    const request = http.expectOne('/api/v1/auth/reset-password');
    expect(request.request.body).toEqual({ token, newPassword: 'Newpass1' });
    request.flush(null, { status: 204, statusText: 'No Content' });
    fixture.detectChanges();
    expect(page.querySelector('[role="status"]')?.textContent).toContain('Senha alterada');
    expect(page.querySelector('input')).toBeNull();
  });
  it('offers a new recovery link after rejection', () => {
    const { fixture, page, http } = setup(true, `token=${token}`);
    fill(page, 'newPassword', 'Newpass1');
    fill(page, 'confirmPassword', 'Newpass1');
    submit(page);
    http
      .expectOne('/api/v1/auth/reset-password')
      .flush({}, { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();
    expect(page.querySelector('[role="alert"]')?.textContent).toContain('expirado');
    expect(page.querySelector('a[href="/recuperar-senha"]')).not.toBeNull();
  });
});
