import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { of } from 'rxjs';
import { Workspace } from './workspace';
import { SessionService } from '../../../../core/auth/data-access/session.service';

const account = { tenantId: 'tenant', role: 'OWNER', companyName: 'Oficina Horizonte' };
describe('Operational workspace', () => {
  const logout = vi.fn(() => of(undefined));
  beforeEach(() => {
    logout.mockClear();
    TestBed.configureTestingModule({
      imports: [Workspace],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: SessionService, useValue: { session: signal(account).asReadonly(), logout } },
      ],
    });
  });
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  function setup() {
    const fixture = TestBed.createComponent(Workspace);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/subscription/access')
      .flush({
        status: 'TRIALING',
        writable: true,
        accessUntil: '2026-12-01T00:00:00Z',
        trialDaysRemaining: 14,
      });
    TestBed.inject(HttpTestingController).expectOne('/api/oficinas/tenant/selecao').flush(account);
    for (const request of TestBed.inject(HttpTestingController).match(
      (r) => r.url === '/api/v1/work-orders',
    )) {
      request.flush({ content: [], totalElements: 0 });
    }
    fixture.detectChanges();
    return { fixture, page: fixture.nativeElement as HTMLElement };
  }
  it('shows upgrade feedback when user creation reaches the plan limit', () => {
    const { fixture, page } = setup();
    fixture.componentInstance['createUser']();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/oficinas/tenant/usuarios')
      .flush({ code: 'PLAN_LIMIT_REACHED' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(page.textContent).toContain('Faça upgrade do plano');
    expect(page.textContent).not.toContain('E-mail já cadastrado.');
  });
  it('shows real empty indicators and enables financial navigation for the owner', () => {
    const { page } = setup();
    expect(page.querySelector('[aria-current="page"]')?.textContent).toContain('Visão geral');
    expect(page.querySelectorAll('.metric > strong')).toHaveLength(4);
    expect(
      [...page.querySelectorAll('.metric > strong')].map((el) => el.textContent).join(',') ===
        '0,0,0,—',
    ).toBe(true);
    expect(page.querySelectorAll('.operation-nav button:disabled')).toHaveLength(0);
    expect(page.querySelector('a[href="/inicio/financeiro"]')?.textContent).toContain('Financeiro');
    expect(page.querySelector('.orders-empty')?.textContent).toContain('Abra uma nova ordem');
    expect(page.querySelector('.profile')?.textContent).toContain('Proprietário');
  });
  it('shows the actual company name and allows collapsing and expanding the sidebar', () => {
    const { fixture, page } = setup();
    expect(page.querySelector('.company strong')?.textContent).toContain('Oficina Horizonte');
    const button = page.querySelector<HTMLButtonElement>('.sidebar-collapse')!;
    button.click();
    fixture.detectChanges();
    expect(page.querySelector('.operation')?.classList.contains('sidebar-collapsed')).toBe(true);
    expect(button.getAttribute('aria-label')).toBe('Expandir menu lateral');
    button.click();
    fixture.detectChanges();
    expect(page.querySelector('.operation')?.classList.contains('sidebar-collapsed')).toBe(false);
  });
  it('opens the mobile navigation and closes it using Escape', () => {
    const { fixture, page } = setup();
    const toggle = page.querySelector<HTMLButtonElement>('.menu-toggle')!;
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    toggle.click();
    fixture.detectChanges();
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(page.querySelector('.sidebar')?.classList.contains('open')).toBe(true);
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    fixture.detectChanges();
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(page.querySelector('.sidebar-backdrop')).toBeNull();
  });
  it('preserves the real company access check', () => {
    const { fixture, page } = setup();
    page.querySelector<HTMLButtonElement>('#verify-access')!.click();
    TestBed.inject(HttpTestingController).expectOne('/api/oficinas/tenant/selecao').flush(account);
    fixture.detectChanges();
    expect(page.querySelector('.operation-status')?.textContent).toContain(
      'Acesso à empresa confirmado',
    );
  });
  it('logs out using the existing service and returns to login', () => {
    const { page } = setup();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    page.querySelector<HTMLButtonElement>('#logout')!.click();
    expect(logout).toHaveBeenCalledOnce();
    expect(navigate).toHaveBeenCalledWith(['/entrar']);
  });
});
