import { Component, DestroyRef, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { CurrencyPipe, DecimalPipe } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { SessionService } from '../../../../core/auth/data-access/session.service';

interface Part {
  id: string;
  sku: string;
  name: string;
  unit: string;
  cost: number;
  salePrice: number;
  minQuantity: number;
  quantityOnHand: number;
  version: number;
}
@Component({
  selector: 'app-parts',
  imports: [FormsModule, CurrencyPipe, DecimalPipe],
  templateUrl: './parts.html',
  styleUrl: './parts.css',
})
export class PartsPanel {
  private readonly http = inject(HttpClient);
  private readonly destroy = inject(DestroyRef);
  protected readonly owner = inject(SessionService).session()?.role === 'OWNER';
  protected readonly parts = signal<Part[]>([]);
  protected readonly busy = signal(false);
  protected readonly error = signal('');
  protected readonly notice = signal('');
  protected readonly deleting = signal<Part | null>(null);
  protected moving: Part | null = null;
  protected movement = { type: 'ENTRY', quantity: 1, unitCost: 0, reason: '' };
  protected startMovement(part: Part): void {
    this.moving = part;
    this.movement = { type: 'ENTRY', quantity: 1, unitCost: part.cost, reason: '' };
  }
  protected saveMovement(): void {
    if (!this.owner || this.busy() || !this.moving) return;
    const adjustment = this.movement.type === 'ADJUSTMENT';
    if (!this.movement.quantity || (!adjustment && this.movement.quantity <= 0)
        || (adjustment && !this.movement.reason.trim())) return;
    this.busy.set(true);
    this.error.set('');
    this.notice.set('');
    this.http.post<Part>(`/api/v1/parts/${this.moving.id}/${adjustment ? 'adjustments' : 'entries'}`, {
      quantity: this.movement.quantity,
      version: this.moving.version,
      ...(adjustment ? { reason: this.movement.reason } : { unitCost: this.movement.unitCost }),
    }).pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: () => {
        this.moving = null;
        this.notice.set('Movimentação registrada.');
        this.load();
      },
      error: (error) => this.fail(error.status),
    });
  }
  protected editing: Part | null = null;
  protected form = this.empty();
  constructor() {
    this.load();
  }
  private empty() {
    return { sku: '', name: '', unit: 'UN', cost: 0, salePrice: 0, minQuantity: 0 };
  }
  protected load(): void {
    this.busy.set(true);
    this.http
      .get<Part[]>('/api/v1/parts')
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (parts) => {
          this.parts.set(parts);
          this.busy.set(false);
        },
        error: () => {
          this.busy.set(false);
          this.error.set('Não foi possível carregar as peças. Tente novamente.');
        },
      });
  }
  protected edit(part: Part): void {
    this.editing = part;
    this.form = {
      sku: part.sku,
      name: part.name,
      unit: part.unit,
      cost: part.cost,
      salePrice: part.salePrice,
      minQuantity: part.minQuantity,
    };
    this.error.set('');
    this.notice.set('');
  }
  protected cancel(): void {
    this.editing = null;
    this.form = this.empty();
  }
  protected save(): void {
    if (!this.owner || this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    this.notice.set('');
    const request = this.editing
      ? this.http.put<Part>(`/api/v1/parts/${this.editing.id}`, {
          ...this.form,
          version: this.editing.version,
        })
      : this.http.post<Part>('/api/v1/parts', this.form);
    request.pipe(takeUntilDestroyed(this.destroy)).subscribe({
      next: () => {
        this.cancel();
        this.notice.set('Peça salva.');
        this.load();
      },
      error: (error) => this.fail(error.status),
    });
  }
  protected remove(): void {
    const part = this.deleting();
    if (!part || !this.owner || this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    this.notice.set('');
    this.http
      .delete(`/api/v1/parts/${part.id}`, { params: { version: part.version } })
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: () => {
          this.deleting.set(null);
          if (this.editing?.id === part.id) this.cancel();
          this.notice.set('Peça excluída.');
          this.load();
        },
        error: (error) => {
          this.deleting.set(null);
          this.fail(error.status);
        },
      });
  }
  private fail(status: number): void {
    this.busy.set(false);
    this.error.set(
      status === 409
        ? 'SKU já cadastrado ou peça alterada por outra operação. Atualize a lista e tente novamente.'
        : 'Não foi possível salvar a alteração. Confira os dados e tente novamente.',
    );
  }
}
