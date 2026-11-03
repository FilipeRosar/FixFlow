import { Component, inject, signal } from "@angular/core";
import { FormBuilder, ReactiveFormsModule, Validators } from "@angular/forms";
import { Router, RouterLink } from "@angular/router";
import { finalize } from "rxjs";
import { HttpErrorResponse } from "@angular/common/http";
import { SessionService } from "../../../../core/auth/data-access/session.service";

@Component({
  selector: "app-login",
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: "./login.html",
})
export class Login {
  private readonly sessions = inject(SessionService);
  private readonly router = inject(Router);
  private readonly formBuilder = inject(FormBuilder);
  protected readonly pending = signal(false);
  protected readonly error = signal("");
  protected readonly registered = signal(false);
  protected readonly form = this.formBuilder.nonNullable.group({
    email: [
      "",
      [Validators.required, Validators.email, Validators.maxLength(254)],
    ],
    password: ["", [Validators.required, Validators.maxLength(72)]],
    tenantId: [""],
  });

  constructor() {
    const state = this.router.currentNavigation()?.extras.state;
    if (state?.["registered"] === true) {
      this.registered.set(true);
      this.form.patchValue({
        email: String(state["email"] ?? ""),
        tenantId: String(state["tenantId"] ?? ""),
      });
    }
  }

  protected submit(): void {
    if (this.pending()) return;
    this.form.controls.email.setValue(this.form.controls.email.value.trim());
    this.form.markAllAsTouched();
    if (this.form.invalid) return;
    this.error.set("");
    this.pending.set(true);
    const value = this.form.getRawValue();
    this.sessions
      .login({
        email: value.email.trim(),
        password: value.password,
        ...(value.tenantId ? { tenantId: value.tenantId } : {}),
      })
      .pipe(finalize(() => this.pending.set(false)))
      .subscribe({
        next: () => {
          this.form.controls.password.reset();
          void this.router.navigate(["/inicio"]);
        },
        error: (error: unknown) => {
          this.form.controls.password.reset();
          this.error.set(
            error instanceof HttpErrorResponse && error.status === 0
              ? "Não foi possível conectar. Tente novamente em instantes."
              : "Não foi possível entrar. Verifique as credenciais ou aguarde antes de tentar novamente.",
          );
        },
      });
  }
}
