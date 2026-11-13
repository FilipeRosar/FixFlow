import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { RegistrationRequest } from '../dto/session';

@Injectable({ providedIn: 'root' })
export class RegistrationService {
  private readonly http = inject(HttpClient);
  register(request: RegistrationRequest) {
    return this.http.post<{ message: string }>('/api/contas', request);
  }
}
