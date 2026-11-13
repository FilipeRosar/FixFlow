import { Component, OnInit, inject, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { finalize } from 'rxjs';
import { Router } from '@angular/router';
import { SessionService } from '../../../../core/auth/data-access/session.service';
export interface CompanySettings {
  logoDataUrl: string | null;
  warrantyTerms: string;
  quoteValidityDays: number;
  allowNegativeStock: boolean;
}
@Component({
  selector: 'app-company-settings',
  imports: [FormsModule],
  templateUrl: './company-settings.html',
  styleUrl: './company-settings.css',
})
export class CompanySettingsPanel implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly session = inject(SessionService);
  private readonly router = inject(Router);
  protected readonly confirmingDeletion = signal(false);
  protected readonly deleting = signal(false);
  protected readonly deletionError = signal('');
  protected deletionPassword = '';
  protected deletionConfirmation = '';
  protected cancelDeletion(): void {
    if (this.deleting()) return;
    this.confirmingDeletion.set(false);
    this.deletionPassword = '';
    this.deletionConfirmation = '';
    this.deletionError.set('');
  }
  protected deleteCompany(): void {
    if (
      this.deleting() ||
      this.saving() ||
      !this.deletionPassword ||
      this.deletionConfirmation !== 'EXCLUIR'
    )
      return;
    this.deleting.set(true);
    this.deletionError.set('');
    this.http
      .delete<void>('/api/v1/tenant', {
        body: { password: this.deletionPassword, confirmation: this.deletionConfirmation },
      })
      .pipe(finalize(() => this.deleting.set(false)))
      .subscribe({
        next: () => {
          this.deletionPassword = '';
          this.session.forgetSession();
          void this.router.navigate(['/entrar']);
        },
        error: (error: HttpErrorResponse) => {
          this.deletionPassword = '';
          this.deletionError.set(
            error.error?.detail || 'Não foi possível excluir a empresa. Tente novamente.',
          );
        },
      });
  }
  readonly saved = output<CompanySettings>();
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly loaded = signal(false);
  protected readonly message = signal('');
  protected readonly failed = signal(false);
  protected readonly readingLogo = signal(false);
  protected settings: CompanySettings = {
    logoDataUrl: null,
    warrantyTerms: '',
    quoteValidityDays: 7,
    allowNegativeStock: false,
  };
  private original = { ...this.settings };
  ngOnInit(): void {
    this.load();
  }
  protected load(): void {
    this.loading.set(true);
    this.message.set('');
    this.http
      .get<CompanySettings>('/api/v1/tenant')
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (value) => {
          this.settings = { ...value };
          this.original = { ...value };
          this.loaded.set(true);
          this.failed.set(false);
        },
        error: () => this.report('Não foi possível carregar as configurações. Tente novamente.'),
      });
  }
  protected reset(): void {
    this.settings = { ...this.original };
    this.message.set('');
    this.failed.set(false);
  }
  protected chooseLogo(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    if (!['image/png', 'image/jpeg'].includes(file.type) || file.size > 524288) {
      this.report('Escolha uma imagem PNG ou JPEG de até 512 KB.');
      return;
    }
    this.readingLogo.set(true);
    const reader = new FileReader();
    reader.onload = () => {
      this.settings.logoDataUrl = reader.result as string;
      this.failed.set(false);
      this.message.set('');
      this.readingLogo.set(false);
    };
    reader.onerror = () => {
      this.report('Não foi possível ler essa imagem.');
      this.readingLogo.set(false);
    };
    reader.readAsDataURL(file);
  }
  protected save(): void {
    if (this.deleting() || this.saving() || !this.loaded() || this.readingLogo()) return;
    this.saving.set(true);
    this.message.set('');
    this.http
      .put<CompanySettings>('/api/v1/tenant', this.settings)
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (value) => {
          this.settings = { ...value };
          this.original = { ...value };
          this.failed.set(false);
          this.message.set('Configurações salvas.');
          this.saved.emit(value);
        },
        error: (error: HttpErrorResponse) =>
          this.report(
            error.status === 400
              ? 'Confira os campos. O logo deve ser PNG ou JPEG, com até 512 KB e 2048 × 2048 pixels.'
              : 'Não foi possível salvar as configurações. Tente novamente.',
          ),
      });
  }
  private report(message: string): void {
    this.failed.set(true);
    this.message.set(message);
  }
}
