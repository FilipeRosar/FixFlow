import { Component, DestroyRef, inject, input, output, signal } from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize } from 'rxjs';
@Component({
  selector: 'app-customer-privacy',
  imports: [FormsModule],
  templateUrl: './customer-privacy.html',
  styleUrls: ['../customers/customers.css', './customer-privacy.css'],
})
export class CustomerPrivacyPanel {
  readonly customerId = input.required<string>();
  readonly anonymized = input(false);
  readonly changed = output<void>();
  private readonly http = inject(HttpClient);
  private readonly document = inject(DOCUMENT);
  private readonly destroy = inject(DestroyRef);
  protected readonly busy = signal(false);
  protected readonly confirming = signal(false);
  protected readonly completed = signal(false);
  protected readonly error = signal('');
  protected readonly notice = signal('');
  protected confirmed = false;
  protected export(): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    this.notice.set('');
    const id = this.customerId();
    this.http
      .get(`/api/v1/customers/${id}/export`, { responseType: 'blob' })
      .pipe(
        takeUntilDestroyed(this.destroy),
        finalize(() => this.busy.set(false)),
      )
      .subscribe({
        next: (blob) => {
          const url = URL.createObjectURL(blob);
          const link = this.document.createElement('a');
          link.href = url;
          link.download = `cliente-${id}.json`;
          this.document.body.appendChild(link);
          link.click();
          link.remove();
          setTimeout(() => URL.revokeObjectURL(url), 1000);
          this.notice.set('Exportação preparada. Confira o arquivo JSON nos downloads.');
        },
        error: () =>
          this.error.set('Não foi possível exportar os dados do cliente. Tente novamente.'),
      });
  }
  protected start(): void {
    this.error.set('');
    this.notice.set('');
    this.confirmed = false;
    this.confirming.set(true);
  }
  protected anonymize(): void {
    if (this.busy() || !this.confirmed || this.anonymized() || this.completed()) return;
    this.busy.set(true);
    this.error.set('');
    this.notice.set('');
    this.http
      .post(`/api/v1/customers/${this.customerId()}/anonymize`, { confirmed: true })
      .pipe(
        takeUntilDestroyed(this.destroy),
        finalize(() => this.busy.set(false)),
      )
      .subscribe({
        next: () => {
          this.completed.set(true);
          this.confirming.set(false);
          this.notice.set('Dados anonimizados. Os registros de atendimento foram preservados.');
          this.changed.emit();
        },
        error: (error: HttpErrorResponse) =>
          this.error.set(
            typeof error.error?.detail === 'string' && error.status < 500
              ? error.error.detail
              : 'Não foi possível anonimizar. Nenhuma alteração foi concluída. Tente novamente.',
          ),
      });
  }
}
