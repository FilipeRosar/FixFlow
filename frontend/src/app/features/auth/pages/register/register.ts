import { Component, inject, signal } from "@angular/core";
import {
  AbstractControl,
  FormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from "@angular/forms";
import { Router, RouterLink } from "@angular/router";
import { HttpErrorResponse } from "@angular/common/http";
import { finalize } from "rxjs";
import { RegistrationService } from "../../../../core/auth/data-access/registration.service";

function passwordLength(control: AbstractControl): ValidationErrors | null {
  return new TextEncoder().encode(String(control.value ?? "")).length > 72
    ? { byteLength: true }
    : null;
}
@Component({
  selector: "app-register",
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: "./register.html",
})
export class Register {
  private readonly registration = inject(RegistrationService);
  private readonly router = inject(Router);
  protected readonly pending = signal(false);
  protected readonly error = signal("");
  protected readonly form = inject(FormBuilder).nonNullable.group({
    companyName: [
      "",
      [
        Validators.required,
        Validators.maxLength(150),
        Validators.pattern(/\S/),
      ],
    ],
    fullName: [
      "",
      [
        Validators.required,
        Validators.maxLength(150),
        Validators.pattern(/\S/),
      ],
    ],
    email: [
      "",
      [Validators.required, Validators.email, Validators.maxLength(254)],
    ],
    password: [
      "",
      [
        Validators.required,
        Validators.minLength(10),
        Validators.maxLength(72),
        passwordLength,
      ],
    ],
  });

  protected submit(): void {
    if (this.pending()) return;
    this.form.controls.email.setValue(this.form.controls.email.value.trim());
    this.form.controls.companyName.setValue(
      this.form.controls.companyName.value.trim(),
    );
    this.form.controls.fullName.setValue(
      this.form.controls.fullName.value.trim(),
    );
    this.form.markAllAsTouched();
    if (this.form.invalid) return;
    this.error.set("");
    this.pending.set(true);
    const value = this.form.getRawValue();
    this.registration
      .register({
        ...value,
        companyName: value.companyName.trim(),
        fullName: value.fullName.trim(),
        email: value.email.trim(),
      })
      .pipe(finalize(() => this.pending.set(false)))
      .subscribe({
        next: (account) => {
          this.form.controls.password.reset();
          void this.router.navigate(["/entrar"], {
            state: {
              registered: true,
              email: value.email.trim(),
              tenantId: account.tenantId,
            },
          });
        },
        error: (error: unknown) => {
          this.error.set(
            error instanceof HttpErrorResponse && error.status === 409
              ? "Este e-mail já está cadastrado. Entre na sua conta."
              : error instanceof HttpErrorResponse && error.status === 0
                ? "Não foi possível conectar. Tente novamente em instantes."
                : "Não foi possível criar a conta. Confira os dados e tente novamente.",
          );
        },
      });
  }
}
