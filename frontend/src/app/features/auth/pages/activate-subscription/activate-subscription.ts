import { Component, effect, inject } from '@angular/core';
import { Router } from '@angular/router';
import { SubscriptionPanel } from '../../../workspace/components/subscription/subscription';
import { SubscriptionAccessState } from '../../../../core/auth/data-access/subscription-access.service';
import { SessionService } from '../../../../core/auth/data-access/session.service';

@Component({
  selector: 'app-activate-subscription',
  imports: [SubscriptionPanel],
  template: `<main style="max-width: 960px; margin: 48px auto; padding: 24px">
    <h1>Ative sua conta</h1>
    <p>Conclua a assinatura do plano escolhido. O acesso à assistência será liberado somente após a confirmação do pagamento.</p>
    <app-subscription />
    <button type="button" (click)="logout()">Sair</button>
  </main>`,
})
export class ActivateSubscription {
  private readonly router = inject(Router);
  private readonly sessions = inject(SessionService);
  private readonly access = inject(SubscriptionAccessState);
  constructor() {
    effect(() => {
      if (this.access.access()?.writable) void this.router.navigate(['/inicio']);
    });
  }
  protected logout(): void {
    this.sessions.logout().subscribe({ next: () => void this.router.navigate(['/entrar']) });
  }
}
