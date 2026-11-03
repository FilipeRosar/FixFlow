import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { of } from 'rxjs';
import { Workspace } from './workspace';
import { SessionService } from '../../../../core/auth/data-access/session.service';

const account = { tenantId: 'tenant', role: 'OWNER' };
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
    return { fixture, page: fixture.nativeElement as HTMLElement };
  }
  it('marks the current area and future modules without making up business data', () => {
    const { page } = setup();
    expect(page.querySelector('[aria-current="page"]')?.textContent).toContain('Visão geral');
    expect(page.querySelectorAll('.metric > strong')).toHaveLength(4);
    expect(
      [...page.querySelectorAll('.metric > strong')].every((el) => el.textContent === '—'),
    ).toBe(true);
    expect(page.querySelectorAll('.operation-nav button:disabled')).toHaveLength(5);
    expect(page.querySelector('.orders-empty')?.textContent).toContain('próxima etapa');
    expect(page.querySelector('.profile')?.textContent).toContain('Proprietário');
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
    expect(page.querySelector('[role="status"]')?.textContent).toContain(
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
