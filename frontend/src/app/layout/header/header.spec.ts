import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { SessionService } from '../../core/auth/data-access/session.service';
import { Session } from '../../core/auth/dto/session';
import { Header } from './header';

describe('Navigation with authentication', () => {
  const session = signal<Session | null>(null);
  beforeEach(() => {
    session.set(null);
    TestBed.configureTestingModule({
      imports: [Header],
      providers: [
        provideRouter([]),
        { provide: SessionService, useValue: { session: session.asReadonly() } },
      ],
    });
  });

  it('offers login and registration to visitors', () => {
    const fixture = TestBed.createComponent(Header);
    fixture.detectChanges();
    const page = fixture.nativeElement as HTMLElement;
    expect(page.querySelector('nav')?.getAttribute('aria-label')).toBe('Navegação principal');
    expect(page.querySelector('a[href="/entrar"]')).not.toBeNull();
    expect(page.querySelector('a[href="/#planos"]')).not.toBeNull();
    expect(page.querySelector('a[href="/inicio"]')).toBeNull();
  });

  it('offers the company area to authenticated users', () => {
    const fixture = TestBed.createComponent(Header);
    fixture.detectChanges();
    session.set({
      accessToken: 'token',
      tokenType: 'Bearer',
      expiresIn: 900,
      userId: 'u',
      tenantId: 't',
      role: 'OWNER',
    });
    fixture.detectChanges();
    const page = fixture.nativeElement as HTMLElement;
    expect(page.querySelector('a[href="/inicio"]')).not.toBeNull();
    expect(page.querySelector('a[href="/entrar"]')).toBeNull();
  });
});
