import { Component, HostListener, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { HttpClient } from '@angular/common/http';
import { SessionService } from '../../../../core/auth/data-access/session.service';
import { Membership } from '../../../../core/auth/dto/session';

@Component({
  selector: 'app-workspace',
  imports: [RouterLink],
  templateUrl: './workspace.html',
  styleUrl: './workspace.css',
})
export class Workspace {
  private readonly sessions = inject(SessionService);
  private readonly router = inject(Router);
  protected readonly session = this.sessions.session;
  protected readonly leaving = signal(false);
  protected readonly menuOpen = signal(false);
  protected readonly today = new Intl.DateTimeFormat('pt-BR', {
    weekday: 'long',
    day: '2-digit',
    month: 'long',
  }).format(new Date());
  protected readonly upcoming = [
    { label: 'Ordens de serviço', icon: 'orders' },
    { label: 'Clientes', icon: 'clients' },
    { label: 'Aparelhos', icon: 'devices' },
    { label: 'Financeiro', icon: 'finance' },
    { label: 'Configurações', icon: 'settings' },
  ];
  protected readonly metrics = [
    {
      label: 'Ordens em andamento',
      icon: 'orders',
      tone: 'purple',
      hint: 'Acompanhe os reparos em execução',
    },
    {
      label: 'Aguardando aprovação',
      icon: 'clock',
      tone: 'amber',
      hint: 'Orçamentos que precisam de retorno',
    },
    {
      label: 'Prontos para entrega',
      icon: 'check',
      tone: 'green',
      hint: 'Equipamentos aguardando retirada',
    },
    {
      label: 'Receita do mês',
      icon: 'finance',
      tone: 'blue',
      hint: 'Visão dos pagamentos registrados',
    },
  ];

  @HostListener('document:keydown.escape')
  protected closeMenu(): void {
    this.menuOpen.set(false);
  }
  private readonly http = inject(HttpClient);
  protected readonly status = signal('');
  protected readonly pending = signal(false);

  protected logout(): void {
    if (this.leaving()) return;
    this.leaving.set(true);
    this.sessions
      .logout()
      .pipe(finalize(() => this.leaving.set(false)))
      .subscribe({
        next: () => {
          void this.router.navigate(['/entrar']);
        },
        error: () =>
          this.status.set('Não foi possível encerrar a sessão no servidor. Tente sair novamente.'),
      });
  }

  protected verifyAccess(): void {
    const session = this.session();
    if (!session || this.pending()) return;
    this.pending.set(true);
    this.http.get<Membership>(`/api/oficinas/${session.tenantId}/selecao`).subscribe({
      next: () => {
        this.status.set('Acesso à empresa confirmado.');
        this.pending.set(false);
      },
      error: () => {
        this.status.set('Não foi possível confirmar o acesso à empresa.');
        this.pending.set(false);
      },
    });
  }
}
