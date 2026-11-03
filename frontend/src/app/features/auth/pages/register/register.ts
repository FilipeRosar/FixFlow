import { Component, DestroyRef, inject, signal } from "@angular/core";
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
import { takeUntilDestroyed } from "@angular/core/rxjs-interop";
import { PostalCodeService } from "../../../../core/auth/data-access/postal-code.service";
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
  private readonly postalCodes = inject(PostalCodeService);
  private readonly destroyRef = inject(DestroyRef);
  private postalCodeVersion = 0;
  protected readonly postalCodePending = signal(false);
  protected readonly postalCodeMessage = signal("");
  protected readonly postalCodeError = signal(false);
  private readonly registration = inject(RegistrationService);
  private readonly router = inject(Router);
  protected readonly pending = signal(false);
  protected readonly showPassword = signal(false);
  protected readonly showConfirmation = signal(false);
  protected readonly error = signal("");
  protected readonly form = inject(FormBuilder).nonNullable.group(
    {
      companyName: [
        "",
        [
          Validators.required,
          Validators.maxLength(150),
          Validators.pattern(/\S/),
        ],
      ],
      firstName: [
        "",
        [
          Validators.required,
          Validators.maxLength(150),
          Validators.pattern(/\S/),
        ],
      ],
      lastName: [
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
      document: [
        "",
        [Validators.required, Validators.pattern(/^(?:[0-9]{11}|[0-9]{14})$/)],
      ],
      street: [
        "",
        [
          Validators.required,
          Validators.maxLength(150),
          Validators.pattern(/\S/),
        ],
      ],
      number: [
        "",
        [
          Validators.required,
          Validators.maxLength(20),
          Validators.pattern(/\S/),
        ],
      ],
      complement: ["", [Validators.maxLength(100)]],
      neighborhood: [
        "",
        [
          Validators.required,
          Validators.maxLength(100),
          Validators.pattern(/\S/),
        ],
      ],
      city: [
        "",
        [
          Validators.required,
          Validators.maxLength(100),
          Validators.pattern(/\S/),
        ],
      ],
      state: [
        "",
        [
          Validators.required,
          Validators.maxLength(2),
          Validators.pattern(/^[A-Za-z]{2}$/),
        ],
      ],
      postalCode: [
        "",
        [
          Validators.required,
          Validators.maxLength(9),
          Validators.pattern(/^[0-9]{5}-?[0-9]{3}$/),
        ],
      ],
      phone: ["", [Validators.required, Validators.pattern(/^[0-9]{10,11}$/)]],
      confirmPassword: ["", Validators.required],
      password: [
        "",
        [
          Validators.required,
          Validators.minLength(8),
          Validators.pattern(/^(?=.*[A-Z])(?=.*[a-z])(?=.*[0-9])[\s\S]*$/),
          Validators.maxLength(72),
          passwordLength,
        ],
      ],
    },
    {
      validators: (control: AbstractControl) =>
        control.get("password")?.value === control.get("confirmPassword")?.value
          ? null
          : { passwordMismatch: true },
    },
  );

  protected postalCodeChanged(event: Event): void {
    const input = event.target as HTMLInputElement;
    const caret = input.selectionStart ?? input.value.length;
    const digitsBeforeCaret = input.value.slice(0, caret).replace(/\D/g, "").length;
    const digits = input.value.replace(/\D/g, "").slice(0, 8);
    const formatted = digits.length > 5 ? `${digits.slice(0, 5)}-${digits.slice(5)}` : digits;
    this.form.controls.postalCode.setValue(formatted);
    const nextCaret = Math.min(digitsBeforeCaret + (digitsBeforeCaret > 5 ? 1 : 0), formatted.length);
    input.setSelectionRange(nextCaret, nextCaret);
    this.postalCodeVersion++;
    this.postalCodeMessage.set("");
    this.postalCodeError.set(false);
  }

  protected lookupPostalCode(): void {
    if (this.postalCodePending() || this.pending()) return;
    const control = this.form.controls.postalCode;
    control.markAsTouched();
    this.postalCodeMessage.set("");
    this.postalCodeError.set(false);
    if (control.invalid) return;
    const postalCode = control.value.replace(/\D/g, "");
    const version = this.postalCodeVersion;
    // Do not overwrite changes made while the lookup is in flight.
    const before = this.form.getRawValue();
    this.postalCodePending.set(true);
    this.postalCodes
      .lookup(postalCode)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.postalCodePending.set(false)),
      )
      .subscribe({
        next: (address) => {
          if (
            version !== this.postalCodeVersion ||
            control.value.replace(/-/g, "") !== postalCode ||
            this.pending()
          )
            return;
          for (const field of [
            "street",
            "neighborhood",
            "city",
            "state",
          ] as const) {
            const target = this.form.controls[field];
            if (target.value === before[field])
              target.setValue(address[field] ?? "");
          }
          this.postalCodeMessage.set(
            "Endereço encontrado. Confira os dados e informe o número e o complemento, se houver.",
          );
        },
        error: (error: unknown) => {
          if (version !== this.postalCodeVersion) return;
          this.postalCodeError.set(true);
          this.postalCodeMessage.set(
            error instanceof HttpErrorResponse && error.status === 404
              ? "CEP não encontrado. Confira o CEP ou preencha o endereço manualmente."
              : "Não foi possível consultar o CEP. Tente novamente ou preencha o endereço manualmente.",
          );
        },
      });
  }

  protected normalizeContact(field: "document" | "phone"): void {
    const control = this.form.controls[field];
    control.setValue(control.value.replace(/[.\/()\s-]/g, ""));
  }

  protected submit(): void {
    if (this.pending() || this.postalCodePending()) return;
    this.form.controls.email.setValue(this.form.controls.email.value.trim());
    this.form.controls.companyName.setValue(
      this.form.controls.companyName.value.trim(),
    );
    for (const field of [
      "firstName",
      "lastName",
      "street",
      "number",
      "complement",
      "neighborhood",
      "city",
      "state",
      "postalCode",
    ] as const) {
      const control = this.form.controls[field];
      control.setValue(control.value.trim());
    }
    this.form.controls.state.setValue(
      this.form.controls.state.value.toUpperCase(),
    );
    this.normalizeContact("document");
    this.normalizeContact("phone");
    this.form.markAllAsTouched();
    if (this.form.invalid) return;
    this.error.set("");
    this.pending.set(true);
    const value = this.form.getRawValue();
    this.registration
      .register({
        document: value.document,
        address: {
          street: value.street,
          number: value.number,
          complement: value.complement,
          neighborhood: value.neighborhood,
          city: value.city,
          state: value.state,
          postalCode: value.postalCode.replace(/\D/g, ""),
        },
        phone: value.phone,
        password: value.password,
        companyName: value.companyName.trim(),
        firstName: value.firstName,
        lastName: value.lastName,
        email: value.email.trim(),
      })
      .pipe(finalize(() => this.pending.set(false)))
      .subscribe({
        next: (account) => {
          this.form.controls.password.reset();
          this.form.controls.confirmPassword.reset();
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
