import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRouteSnapshot, provideRouter, Router, RouterStateSnapshot, UrlTree } from '@angular/router';
import { firstValueFrom, Observable, of } from 'rxjs';
import { sessionGuard } from './session.guard';
import { SessionService } from '../data-access/session.service';

describe('Payment access gate', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [
    provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
    { provide: SessionService, useValue: { ensureSession: () => of({ role: 'OWNER' }) } },
  ] }));
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  async function check(page: string, status: string) {
    const result = firstValueFrom(TestBed.runInInjectionContext(() => sessionGuard(
      { data: { page } } as unknown as ActivatedRouteSnapshot, {} as RouterStateSnapshot,
    )) as Observable<boolean | UrlTree>);
    TestBed.inject(HttpTestingController).expectOne('/api/v1/subscription/access').flush({
      status, writable: status === 'ACTIVE' || status === 'TRIALING',
    });
    return result;
  }
  it('redirects unpaid accounts away from operational pages', async () => {
    expect(TestBed.inject(Router).serializeUrl(await check('orders', 'PAYMENT_REQUIRED') as UrlTree))
      .toBe('/ativar-assinatura');
  });
  it('allows the checkout before payment', async () => {
    expect(await check('activation', 'PAYMENT_REQUIRED')).toBe(true);
  });
  it('releases access after confirmed payment and preserves Starter trial access', async () => {
    expect(await check('orders', 'ACTIVE')).toBe(true);
    expect(await check('orders', 'TRIALING')).toBe(true);
    expect(TestBed.inject(Router).serializeUrl(await check('activation', 'ACTIVE') as UrlTree)).toBe('/inicio');
  });
});
