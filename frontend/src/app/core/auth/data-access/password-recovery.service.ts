import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
@Injectable({ providedIn: 'root' })
export class PasswordRecoveryService {
  private readonly http = inject(HttpClient);
  private readonly options = { headers: { 'X-Auth-Request': 'true' }, withCredentials: true };
  request(email: string) {
    return this.http.post<{ message: string }>(
      '/api/v1/auth/forgot-password',
      { email },
      this.options,
    );
  }
  reset(token: string, newPassword: string) {
    return this.http.post<void>(
      '/api/v1/auth/reset-password',
      { token, newPassword },
      this.options,
    );
  }
}
