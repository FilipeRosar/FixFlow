import { Injectable, inject, signal } from "@angular/core";
import { HttpClient } from "@angular/common/http";
import {
  Observable,
  catchError,
  finalize,
  of,
  shareReplay,
  tap,
  throwError,
} from "rxjs";
import { LoginRequest, Session } from "../dto/session";

@Injectable({ providedIn: "root" })
export class SessionService {
  private readonly http = inject(HttpClient);
  private readonly current = signal<Session | null>(null);
  readonly session = this.current.asReadonly();
  private pendingRefresh?: Observable<Session>;
  private expiresAt = 0;
  private readonly authOptions = {
    headers: { "X-Auth-Request": "true" },
    withCredentials: true,
  };

  login(request: LoginRequest): Observable<Session> {
    return this.http
      .post<Session>("/api/v1/auth/login", request, this.authOptions)
      .pipe(tap((session) => this.accept(session)));
  }

  ensureSession(): Observable<Session> {
    const current = this.current();
    return current && this.expiresAt > Date.now() + 5000
      ? of(current)
      : this.refresh();
  }

  refresh(): Observable<Session> {
    if (!this.pendingRefresh) {
      this.pendingRefresh = this.http
        .post<Session>("/api/v1/auth/refresh", {}, this.authOptions)
        .pipe(
          tap((session) => this.accept(session)),
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

  private accept(session: Session): void {
    this.expiresAt = Date.now() + session.expiresIn * 1000;
    this.current.set(session);
  }

  private clear(): void {
    this.current.set(null);
    this.expiresAt = 0;
  }
}
