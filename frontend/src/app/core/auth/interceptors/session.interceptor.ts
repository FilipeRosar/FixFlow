import { SubscriptionAccessState } from '../data-access/subscription-access.service';
import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, switchMap, throwError } from 'rxjs';
import { SessionService } from '../data-access/session.service';

export const sessionInterceptor: HttpInterceptorFn = (request, next) => {
  if (
    !request.url.startsWith('/api/') ||
    request.url.startsWith('/api/v1/auth/') ||
    request.url.startsWith('/api/contas') ||
    request.url.startsWith('/api/enderecos/cep/')
  ) {
    return next(request);
  }
  const sessions = inject(SessionService);
  const subscriptionState = inject(SubscriptionAccessState);
  const router = inject(Router);
  const send = (token: string) =>
    next(request.clone({ setHeaders: { Authorization: `Bearer ${token}` } }));
  return sessions.ensureSession().pipe(
    switchMap((session) =>
      send(session.accessToken).pipe(
        catchError((error: unknown) =>
          error instanceof HttpErrorResponse && error.status === 401
            ? sessions.refresh().pipe(switchMap((renewed) => send(renewed.accessToken)))
            : throwError(() => error),
        ),
      ),
    ),
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 402) {
        subscriptionState.restricted.set(true);
        if (error.error?.code === 'PAYMENT_REQUIRED') void router.navigate(['/ativar-assinatura']);
      }
      if (error instanceof HttpErrorResponse && error.status === 401)
        void router.navigate(['/entrar']);
      return throwError(() => error);
    }),
  );
};
