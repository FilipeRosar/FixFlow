import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { SessionService } from './session.service';
import { sessionInterceptor } from '../interceptors/session.interceptor';
import { Session } from '../dto/session';

const session: Session = {
  accessToken: 'access-1',
  tokenType: 'Bearer',
  expiresIn: 900,
  userId: 'user',
  tenantId: 'tenant',
  role: 'OWNER',
};

describe('Session lifecycle', () => {
  let service: SessionService;
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([sessionInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    service = TestBed.inject(SessionService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('keeps the access token in memory and sends the anti-CSRF header', () => {
    const storage = vi.spyOn(Storage.prototype, 'setItem');
    service.login({ email: 'owner@example.com', password: 'Owner1234!' }).subscribe();
    const request = http.expectOne('/api/v1/auth/login');
    expect(request.request.headers.get('X-Auth-Request')).toBe('true');
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush(session);
    expect(service.session()?.accessToken).toBe('access-1');
    expect(storage).not.toHaveBeenCalled();
    storage.mockRestore();
  });

  it('coalesces simultaneous refreshes into a single cookie rotation', () => {
    const received: string[] = [];
    service.refresh().subscribe((value) => received.push(value.accessToken));
    service.refresh().subscribe((value) => received.push(value.accessToken));
    const request = http.expectOne('/api/v1/auth/refresh');
    expect(request.request.withCredentials).toBe(true);
    request.flush(session);
    expect(received).toEqual(['access-1', 'access-1']);
    service.refresh().subscribe();
    http.expectOne('/api/v1/auth/refresh').flush({ ...session, accessToken: 'access-2' });
    expect(service.session()?.accessToken).toBe('access-2');
  });

  it('restores a session after a page reload through the HttpOnly cookie', () => {
    expect(service.session()).toBeNull();
    service.ensureSession().subscribe();
    http.expectOne('/api/v1/auth/refresh').flush(session);
    service.ensureSession().subscribe();
    http.expectNone('/api/v1/auth/refresh');
    expect(service.session()?.tenantId).toBe('tenant');
  });

  it('clears the in-memory session when refresh is rejected', () => {
    service.login({ email: 'owner@example.com', password: 'Owner1234!' }).subscribe();
    http.expectOne('/api/v1/auth/login').flush(session);
    service.refresh().subscribe({ error: () => {} });
    http.expectOne('/api/v1/auth/refresh').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(service.session()).toBeNull();
  });

  it('retries a rejected protected request once with a renewed token', () => {
    service.login({ email: 'owner@example.com', password: 'Owner1234!' }).subscribe();
    http.expectOne('/api/v1/auth/login').flush(session);
    TestBed.inject(HttpClient).get('/api/oficinas/tenant/selecao').subscribe();
    const first = http.expectOne('/api/oficinas/tenant/selecao');
    expect(first.request.headers.get('Authorization')).toBe('Bearer access-1');
    first.flush({}, { status: 401, statusText: 'Unauthorized' });
    http.expectOne('/api/v1/auth/refresh').flush({ ...session, accessToken: 'access-2' });
    const retry = http.expectOne('/api/oficinas/tenant/selecao');
    expect(retry.request.headers.get('Authorization')).toBe('Bearer access-2');
    retry.flush({ role: 'OWNER' });
  });

  it('never sends the access token to third-party URLs', () => {
    TestBed.inject(HttpClient).get('https://other.example/data').subscribe();
    const request = http.expectOne('https://other.example/data');
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush({});
  });
});

describe('Logout', () => {
  let service: SessionService;
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([sessionInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    service = TestBed.inject(SessionService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());
  it('clears memory and revokes the server cookie without attempting a refresh', () => {
    service.login({ email: 'owner@example.com', password: 'Owner1234!' }).subscribe();
    http.expectOne('/api/v1/auth/login').flush(session);
    service.logout().subscribe();
    expect(service.session()).toBeNull();
    const request = http.expectOne('/api/v1/auth/logout');
    expect(request.request.headers.get('X-Auth-Request')).toBe('true');
    expect(request.request.withCredentials).toBe(true);
    request.flush(null, { status: 204, statusText: 'No Content' });
    service.ensureSession().subscribe({ error: () => {} });
    http.expectNone('/api/v1/auth/refresh');
  });
  it('waits for pending refresh and does not let its late response restore the session', () => {
    service.refresh().subscribe({ error: () => {} });
    service.logout().subscribe();
    http.expectNone('/api/v1/auth/logout');
    http.expectOne('/api/v1/auth/refresh').flush(session);
    expect(service.session()).toBeNull();
    http.expectOne('/api/v1/auth/logout').flush(null, { status: 204, statusText: 'No Content' });
    expect(service.session()).toBeNull();
  });
});
