import { toSignal } from '@angular/core/rxjs-interop';
import {
  CompanySettingsPanel,
  CompanySettings,
} from '../../components/company-settings/company-settings';
import { FormsModule } from '@angular/forms';
import { Component, HostListener, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { finalize, map } from 'rxjs';
import { HttpClient } from '@angular/common/http';
import { SessionService } from '../../../../core/auth/data-access/session.service';
import { Membership } from '../../../../core/auth/dto/session';

@Component({
  selector: 'app-workspace',
  imports: [RouterLink, FormsModule, CompanySettingsPanel],
  templateUrl: './workspace.html',
  styleUrl: './workspace.css',
})
export class Workspace implements OnInit {
  private readonly sessions = inject(SessionService);
  private readonly router = inject(Router);
  protected readonly session = this.sessions.session;
  protected readonly companyName = signal('');
  protected readonly companyLogo = signal<string | null>(null);
  protected readonly settingsPage = toSignal(
    inject(ActivatedRoute).data.pipe(map((data) => data['page'] === 'settings')),
    { initialValue: false },
  );
  protected applyCompanySettings(settings: CompanySettings): void {
    this.companyLogo.set(settings.logoDataUrl);
  }

  protected readonly sidebarCollapsed = signal(false);
  ngOnInit(): void {
    const session = this.session();
    if (!session) return;
    this.http.get<Membership>(`/api/oficinas/${session.tenantId}/selecao`).subscribe({
      next: (membership) => {
        this.companyName.set(membership.companyName);
        this.companyLogo.set(membership.logoDataUrl ?? null);
      },
      error: () =>
        this.status.set(
          'Não foi possível carregar o nome da empresa. Tente verificar o acesso novamente.',
        ),
    });
  }
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

  protected readonly users = signal<
    { userId: string; fullName: string; email: string; role: string; status: string }[]
  >([]);
  protected readonly usersOpen = signal(false);
  protected readonly loadingUsers = signal(false);
  protected readonly usersError = signal('');
  protected readonly savingUser = signal(false);
  protected userName = '';
  protected userEmail = '';
  protected userPassword = '';
  protected userRole = 'ATTENDANT';
  private usersUrl(): string {
    return `/api/oficinas/${this.session()?.tenantId}/usuarios`;
  }
  protected loadUsers(): void {
    if (this.loadingUsers()) return;
    this.usersOpen.set(true);
    this.loadingUsers.set(true);
    this.usersError.set('');
    this.http
      .get<{ userId: string; fullName: string; email: string; role: string; status: string }[]>(
        this.usersUrl(),
      )
      .pipe(finalize(() => this.loadingUsers.set(false)))
      .subscribe({
        next: (users) => this.users.set(users),
        error: () => this.usersError.set('Não foi possível carregar os usuários.'),
      });
  }
  protected createUser(): void {
    if (this.savingUser()) return;
    this.savingUser.set(true);
    this.http
      .post(this.usersUrl(), {
        fullName: this.userName,
        email: this.userEmail,
        password: this.userPassword,
        role: this.userRole,
      })
      .pipe(finalize(() => this.savingUser.set(false)))
      .subscribe({
        next: () => {
          this.userName = '';
          this.userEmail = '';
          this.userPassword = '';
          this.status.set('Usuário criado.');
          this.loadUsers();
        },
        error: (error) =>
          this.status.set(
            error.error?.code === 'PLAN_LIMIT_REACHED'
              ? 'Limite de usuários do plano atingido. Faça upgrade do plano para continuar criando.'
              : error.status === 409 ? 'E-mail já cadastrado.' : 'Confira os dados e tente novamente.',
          ),
      });
  }
  protected changeUser(userId: string, role: string | null): void {
    if (this.savingUser()) return;
    this.savingUser.set(true);
    const operation =
      role === null
        ? this.http.delete(`${this.usersUrl()}/${userId}`)
        : this.http.patch(`${this.usersUrl()}/${userId}/role`, { role });
    operation.pipe(finalize(() => this.savingUser.set(false))).subscribe({
      next: () => {
        this.status.set('Usuário atualizado.');
        this.loadUsers();
      },
      error: (error) => {
        this.status.set(
          error.status === 409
            ? 'A empresa precisa de um proprietário ativo.'
            : 'Não foi possível atualizar o usuário.',
        );
        this.loadUsers();
      },
    });
  }

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
      next: (membership) => {
        this.companyName.set(membership.companyName);
        this.companyLogo.set(membership.logoDataUrl ?? null);
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
