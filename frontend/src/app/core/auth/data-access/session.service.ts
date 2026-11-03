import { Injectable, inject, signal } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import {
  Observable,
  catchError,
  finalize,
  of,
  shareReplay,
  tap,
  switchMap,
  throwError,
} from 'rxjs';
import { LoginRequest, Session } from '../dto/session';

@Injectable({ providedIn: 'root' })
export class SessionService {
  private readonly http = inject(HttpClient);
  private readonly current = signal<Session | null>(null);
  readonly session = this.current.asReadonly();
  private pendingRefresh?: Observable<Session>;
  private expiresAt = 0;
  private epoch = 0;
  private loggedOut = false;
  private readonly authOptions = {
    headers: { 'X-Auth-Request': 'true' },
    withCredentials: true,
  };

  login(request: LoginRequest): Observable<Session> {
    this.loggedOut = false;
    const epoch = ++this.epoch;
    return this.http
      .post<Session>('/api/v1/auth/login', request, this.authOptions)
      .pipe(tap((session) => this.acceptCurrent(session, epoch)));
  }

  ensureSession(): Observable<Session> {
    const current = this.current();
    return current && this.expiresAt > Date.now() + 5000 ? of(current) : this.refresh();
  }

  refresh(): Observable<Session> {
    if (this.loggedOut) return throwError(() => new HttpErrorResponse({ status: 401 }));
    if (!this.pendingRefresh) {
      const epoch = this.epoch;
      this.pendingRefresh = this.http
        .post<Session>('/api/v1/auth/refresh', {}, this.authOptions)
        .pipe(
          tap((session) => this.acceptCurrent(session, epoch)),
          catchError((error: unknown) => {
            this.clear();
            return throwError(() => error);
          }),
          finalize(() => {
            this.pendingRefresh = undefined;
          }),
          shareReplay({ bufferSize: 1, refCount: false }),
        );
    }
    return this.pendingRefresh;
  }

  logout(): Observable<void> {
    this.loggedOut = true;
    ++this.epoch;
    this.clear();
    // A pending refresh may replace the cookie: revoke its result before leaving.
    const refresh = this.pendingRefresh?.pipe(catchError(() => of(null))) ?? of(null);
    return refresh.pipe(
      switchMap(() => this.http.post<void>('/api/v1/auth/logout', {}, this.authOptions)),
      finalize(() => this.clear()),
    );
  }

  forgetSession(): void {
    this.loggedOut = true;
    ++this.epoch;
    this.clear();
  }

  private acceptCurrent(session: Session, epoch: number): void {
    if (epoch !== this.epoch || this.loggedOut) throw new HttpErrorResponse({ status: 401 });
    this.accept(session);
  }

  private accept(session: Session): void {
    this.expiresAt = Date.now() + session.expiresIn * 1000;
    this.current.set(session);
  }

  private clear(): void {
    this.current.set(null);
    this.expiresAt = 0;
  }
}
