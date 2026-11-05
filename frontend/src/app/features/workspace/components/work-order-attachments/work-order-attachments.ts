import { Component, DestroyRef, inject, input, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
interface Attachment {
  id: string;
  originalName: string;
  contentType: string;
  sizeBytes: number;
  createdAt: string;
}
interface Download {
  url: string;
  expiresAt: string;
}
@Component({
  selector: 'app-work-order-attachments',
  imports: [DatePipe],
  templateUrl: './work-order-attachments.html',
  styleUrls: ['../customers/customers.css', './work-order-attachments.css'],
})
export class WorkOrderAttachmentsPanel {
  readonly orderId = input.required<string>();
  private readonly http = inject(HttpClient);
  private readonly destroy = inject(DestroyRef);
  protected readonly open = signal(false);
  protected readonly loaded = signal(false);
  protected readonly loading = signal(false);
  protected readonly uploading = signal(false);
  protected readonly downloading = signal<string | null>(null);
  protected readonly attachments = signal<Attachment[]>([]);
  protected readonly selected = signal<File | null>(null);
  protected readonly error = signal('');
  protected readonly notice = signal('');
  protected readonly download = signal<(Download & { id: string }) | null>(null);
  private uploadId = '';
  private expirationTimer: ReturnType<typeof setTimeout> | undefined;
  constructor() {
    this.destroy.onDestroy(() => clearTimeout(this.expirationTimer));
  }
  protected toggle(): void {
    this.open.update((value) => !value);
    if (this.open() && !this.loaded()) this.load();
  }
  protected load(): void {
    if (this.loading() || this.uploading()) return;
    this.loading.set(true);
    this.error.set('');
    this.http
      .get<Attachment[]>(`/api/v1/work-orders/${this.orderId()}/attachments`)
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (files) => {
          this.attachments.set(files);
          this.loaded.set(true);
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          this.error.set('Não foi possível carregar os anexos. Tente novamente.');
        },
      });
  }
  protected choose(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0] ?? null;
    this.selected.set(null);
    this.error.set('');
    this.notice.set('');
    this.uploadId = '';
    if (!file) return;
    if (file.size === 0 || file.size > 10 * 1024 * 1024) {
      this.error.set('Selecione um arquivo não vazio de até 10 MB.');
      return;
    }
    if (!['image/jpeg', 'image/png', 'image/webp', 'application/pdf'].includes(file.type)) {
      this.error.set('Selecione uma imagem JPEG, PNG, WebP ou um PDF.');
      return;
    }
    this.selected.set(file);
    this.uploadId = crypto.randomUUID();
  }
  protected upload(fileInput: HTMLInputElement): void {
    const file = this.selected();
    if (
      !file ||
      !this.loaded() ||
      this.loading() ||
      this.uploading() ||
      this.attachments().length >= 20
    )
      return;
    const data = new FormData();
    data.append('file', file);
    data.append('uploadId', this.uploadId);
    this.uploading.set(true);
    this.error.set('');
    this.notice.set('');
    this.http
      .post<Attachment>(`/api/v1/work-orders/${this.orderId()}/attachments`, data)
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (attachment) => {
          this.attachments.update((files) => [
            attachment,
            ...files.filter((f) => f.id !== attachment.id),
          ]);
          this.uploading.set(false);
          this.selected.set(null);
          this.uploadId = '';
          fileInput.value = '';
          this.notice.set('Arquivo anexado à ordem de serviço.');
        },
        error: (error) => {
          this.uploading.set(false);
          this.error.set(
            typeof error.error?.detail === 'string' && error.status < 600
              ? error.error.detail
              : 'Não foi possível confirmar o envio. Tente novamente com o mesmo arquivo.',
          );
        },
      });
  }
  protected generateDownload(file: Attachment): void {
    if (this.downloading()) return;
    this.downloading.set(file.id);
    this.error.set('');
    this.http
      .get<Download>(`/api/v1/attachments/${file.id}/download-url`)
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (signed) => {
          this.downloading.set(null);
          this.download.set({ ...signed, id: file.id });
          clearTimeout(this.expirationTimer);
          this.expirationTimer = setTimeout(
            () => this.download.set(null),
            Math.max(0, Date.parse(signed.expiresAt) - Date.now()),
          );
        },
        error: () => {
          this.downloading.set(null);
          this.error.set('Não foi possível gerar o link de download. Tente novamente.');
        },
      });
  }
  protected size(bytes: number): string {
    return (
      new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 1 }).format(
        bytes < 1024 * 1024 ? bytes / 1024 : bytes / (1024 * 1024),
      ) + (bytes < 1024 * 1024 ? ' KB' : ' MB')
    );
  }
}
