import { Component, DestroyRef, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
@Component({
  selector: 'app-confirm-email',
  imports: [RouterLink],
  template: ` <section class="auth-layout">
    <div class="auth-intro">
      <span class="eyebrow">Só mais um passo</span>
      <h1>Confirme seu e-mail.<br />Continue seu cadastro.</h1>
      <p>Starter inclui 14 dias de teste. Professional e Business são ativados após o pagamento.</p>
    </div>
    <div class="card">
      <h2>Confirmar e-mail</h2>
      <p class="muted">
        Vamos criar sua assistência e sua conta de proprietário no plano escolhido.
      </p>
      @if (error()) {
        <p class="error" role="alert">{{ error() }}</p>
      }
      @if (confirmed()) {
        <p class="notice" role="status">
          E-mail confirmado. Entre para continuar. Nos planos Profissional e Business, conclua o pagamento para liberar o acesso.
        </p>
        <a class="button full" routerLink="/entrar" [state]="loginState()"
          >Continuar cadastro</a
        >
      } @else if (hasToken) {
        <button class="button full" type="button" (click)="confirm()" [disabled]="pending()">
          {{ pending() ? 'Confirmando…' : 'Confirmar e-mail' }}
        </button>
      } @else {
        <p class="error" role="alert">
          Abra o link enviado para seu e-mail para confirmar o cadastro.
        </p>
      }
      <p class="form-footer">
        <a routerLink="/cadastro">Solicitar novo link</a> ·
        <a routerLink="/entrar">Já confirmei. Entrar</a>
      </p>
    </div>
  </section>`,
})
export class ConfirmEmail {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly destroy = inject(DestroyRef);
  private readonly token =
    new URLSearchParams(inject(ActivatedRoute).snapshot.fragment ?? '').get('token') ?? '';
  protected readonly hasToken = /^[A-Za-z0-9_-]{43}$/.test(this.token);
  protected readonly pending = signal(false);
  protected readonly confirmed = signal(false);
  protected readonly error = signal('');
  protected readonly loginState = signal<Record<string, unknown>>({});
  constructor() {
    if (this.hasToken) void this.router.navigate([], { fragment: undefined, replaceUrl: true });
  }
  protected confirm(): void {
    if (!this.hasToken || this.pending()) return;
    this.pending.set(true);
    this.error.set('');
    this.http
      .post<{ email: string; tenantId: string }>('/api/contas/confirmar', { token: this.token })
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (account) => {
          this.pending.set(false);
          this.confirmed.set(true);
          this.loginState.set({
            registered: true,
            email: account.email,
            tenantId: account.tenantId,
          });
        },
        error: (error) => {
          this.pending.set(false);
          this.error.set(error.error?.detail || 'Não foi possível confirmar. Tente novamente.');
        },
      });
  }
}
