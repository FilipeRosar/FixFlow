import { Component, inject, signal } from "@angular/core";
import { HttpClient } from "@angular/common/http";
import { SessionService } from "../../../../core/auth/data-access/session.service";
import { Membership } from "../../../../core/auth/dto/session";

@Component({ selector: "app-workspace", templateUrl: "./workspace.html" })
export class Workspace {
  protected readonly session = inject(SessionService).session;
  private readonly http = inject(HttpClient);
  protected readonly status = signal("");
  protected readonly pending = signal(false);

  protected verifyAccess(): void {
    const session = this.session();
    if (!session || this.pending()) return;
    this.pending.set(true);
    this.http
      .get<Membership>(`/api/oficinas/${session.tenantId}/selecao`)
      .subscribe({
        next: () => {
          this.status.set("Acesso à empresa confirmado.");
          this.pending.set(false);
        },
        error: () => {
          this.status.set("Não foi possível confirmar o acesso à empresa.");
          this.pending.set(false);
        },
      });
  }
}
