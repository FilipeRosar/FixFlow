import { Injectable, signal } from '@angular/core';
export interface SubscriptionAccess {
  status: string;
  writable: boolean;
  accessUntil: string;
  trialDaysRemaining: number;
}
@Injectable({ providedIn: 'root' })
export class SubscriptionAccessState {
  readonly access = signal<SubscriptionAccess | null>(null);
  readonly restricted = signal(false);
  update(access: SubscriptionAccess): void {
    this.access.set(access);
    this.restricted.set(!access.writable);
  }
}
