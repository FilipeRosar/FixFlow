import { Component, inject, signal } from '@angular/core';
import { Location } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { AbstractControl, FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize, Observable } from 'rxjs';
import { PasswordRecoveryService } from '../../../../core/auth/data-access/password-recovery.service';
import { SessionService } from '../../../../core/auth/data-access/session.service';
@Component({
  selector: 'app-recovery',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './recovery.html',
})
export class Recovery {
  private readonly api = inject(PasswordRecoveryService);
  private readonly sessions = inject(SessionService);
  private readonly route = inject(ActivatedRoute);
  private readonly location = inject(Location);
  protected readonly reset = this.route.snapshot.data['reset'] === true;
  private token = new URLSearchParams(this.route.snapshot.fragment ?? '').get('token') ?? '';
  protected readonly validLink = signal(/^[A-Za-z0-9_-]{43}$/.test(this.token));
  protected readonly pending = signal(false);
  protected readonly done = signal(false);
  protected readonly error = signal('');
  protected readonly showPassword = signal(false);
  private readonly fb = inject(FormBuilder);
  protected readonly emailForm = this.fb.nonNullable.group({
    email: ['', [Validators.required, Validators.email, Validators.maxLength(254)]],
  });
  protected readonly passwordForm = this.fb.nonNullable.group(
    {
      newPassword: [
        '',
        [
          Validators.required,
          Validators.minLength(8),
          Validators.maxLength(72),
          Validators.pattern(/^(?=.*[A-Z])(?=.*[a-z])(?=.*[0-9])[\s\S]*$/),
          (control: AbstractControl) =>
            new TextEncoder().encode(String(control.value ?? '')).length > 72
              ? { tooLong: true }
              : null,
        ],
      ],
      confirmPassword: ['', Validators.required],
    },
    {
      validators: (form: AbstractControl) =>
        form.get('newPassword')?.value === form.get('confirmPassword')?.value
          ? null
          : { mismatch: true },
    },
  );
  constructor() {
    // Keep the reset secret out of browser history and subsequent navigation URLs.
    if (this.reset) this.location.replaceState('/redefinir-senha');
    else this.token = '';
  }
  protected submit(): void {
    if (this.pending() || this.done()) return;
    if (this.reset) {
      this.passwordForm.markAllAsTouched();
      if (!this.validLink() || this.passwordForm.invalid) return;
    } else {
      this.emailForm.controls.email.setValue(this.emailForm.controls.email.value.trim());
      this.emailForm.markAllAsTouched();
      if (this.emailForm.invalid) return;
    }
    this.pending.set(true);
    this.error.set('');
    const request: Observable<unknown> = this.reset
      ? this.api.reset(this.token, this.passwordForm.controls.newPassword.value)
      : this.api.request(this.emailForm.controls.email.value);
    request.pipe(finalize(() => this.pending.set(false))).subscribe({
      next: () => {
        this.done.set(true);
        this.passwordForm.reset();
        this.token = '';
        if (this.reset) this.sessions.forgetSession();
      },
      error: () =>
        this.error.set(
          this.reset
            ? 'Não foi possível redefinir a senha. O link pode ter expirado ou já ter sido usado. Solicite um novo link ou tente novamente.'
            : 'Não foi possível enviar a solicitação. Tente novamente em instantes.',
        ),
    });
  }
}
