import { inject } from "@angular/core";
import { CanActivateFn, Router } from "@angular/router";
import { catchError, map, of } from "rxjs";
import { SessionService } from "../data-access/session.service";

export const sessionGuard: CanActivateFn = () => {
  const router = inject(Router);
  return inject(SessionService)
    .ensureSession()
    .pipe(
      map(() => true),
      catchError(() => of(router.createUrlTree(["/entrar"]))),
    );
};
