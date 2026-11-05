import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { SessionService } from '../data-access/session.service';

export const sessionGuard: CanActivateFn = (route) => {
  const router = inject(Router);
  return inject(SessionService)
    .ensureSession()
    .pipe(
      map((session) =>
        !route.data['requiredRole'] || session.role === route.data['requiredRole']
          ? true
          : router.createUrlTree(['/inicio']),
      ),
      catchError(() => of(router.createUrlTree(['/entrar']))),
    );
};
