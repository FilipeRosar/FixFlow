import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, map, of, switchMap } from 'rxjs';
import { HttpClient } from '@angular/common/http';
import { SubscriptionAccess, SubscriptionAccessState } from '../data-access/subscription-access.service';
import { SessionService } from '../data-access/session.service';

export const sessionGuard: CanActivateFn = (route) => {
  const router = inject(Router);
  const http = inject(HttpClient);
  const accessState = inject(SubscriptionAccessState);
  return inject(SessionService)
    .ensureSession()
    .pipe(
      switchMap((session) => http.get<SubscriptionAccess>('/api/v1/subscription/access').pipe(
        map((access) => {
          accessState.update(access);
          if (access.status === 'PAYMENT_REQUIRED')
            return route.data['page'] === 'activation' ? true : router.createUrlTree(['/ativar-assinatura'], { queryParams: route.queryParams });
          if (route.data['page'] === 'activation') return router.createUrlTree(['/inicio']);
          return !route.data['requiredRole'] || session.role === route.data['requiredRole']
            ? true : router.createUrlTree(['/inicio']);
        }),
      )),
      catchError(() => of(router.createUrlTree(['/entrar']))),
    );
};
