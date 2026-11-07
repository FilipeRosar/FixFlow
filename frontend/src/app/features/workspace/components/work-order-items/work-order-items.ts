import {
  Component,
  DestroyRef,
  LOCALE_ID,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { CurrencyPipe, DecimalPipe, registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { SessionService } from '../../../../core/auth/data-access/session.service';

registerLocaleData(localePt, 'pt-BR');

interface Item {
  id: string;
  type: string;
  partId: string | null;
  description: string;
  quantity: number;
  unitPrice: number;
  unitCost: number;
}
interface Part {
  id: string;
  sku: string;
  name: string;
  quantityOnHand: number;
  salePrice: number;
}
interface Saved {
  items: Item[];
  workOrderVersion: number;
}
@Component({
  selector: 'app-work-order-items',
  providers: [{ provide: LOCALE_ID, useValue: 'pt-BR' }],
  imports: [FormsModule, CurrencyPipe, DecimalPipe],
  templateUrl: './work-order-items.html',
  styleUrls: ['../customers/customers.css', './work-order-items.css'],
})
export class WorkOrderItemsPanel {
  readonly order = input.required<{ id: string; version: number; status: string }>();
  readonly versionChanged = output<number>();
  readonly busyChanged = output<boolean>();
  readonly blocked = input(false);
  private readonly http = inject(HttpClient);
  private readonly destroy = inject(DestroyRef);
  protected readonly role = inject(SessionService).session()?.role;
  protected readonly open = signal(false);
  protected readonly loaded = signal(false);
  protected readonly loading = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal('');
  protected readonly notice = signal('');
  protected readonly items = signal<Item[]>([]);
  protected readonly parts = signal<Part[]>([]);
  protected readonly partsError = signal('');
  protected readonly partsLoading = signal(false);
  protected readonly total = computed(() =>
    this.items().reduce((sum, item) => sum + item.quantity * item.unitPrice, 0),
  );
  protected readonly canEdit = computed(
    () =>
      (this.role === 'OWNER' || this.role === 'TECHNICIAN') &&
      this.order().status === 'EM_MANUTENCAO',
  );
  protected type = 'SERVICE';
  protected partId = '';
  protected description = '';
  protected quantity = 1;
  protected unitPrice = 0;
  private pendingAdd: { key: string; id: string } | null = null;
  protected sku = '';
  protected partName = '';
  protected partCost = 0;
  protected partPrice = 0;
  protected toggle(): void {
    this.open.update((value) => !value);
    if (this.open() && !this.loaded()) this.load();
  }
  protected load(): void {
    if (this.loading() || this.busy() || this.blocked()) return;
    this.loading.set(true);
    this.error.set('');
    this.http
      .get<Saved>(`/api/v1/work-orders/${this.order().id}/items`)
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (data) => {
          this.items.set(data.items);
          this.versionChanged.emit(data.workOrderVersion);
          this.loaded.set(true);
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          this.error.set('Não foi possível carregar os itens. Tente novamente.');
        },
      });
    if (this.canEdit()) this.loadParts();
  }
  protected loadParts(): void {
    if (this.partsLoading()) return;
    this.partsLoading.set(true);
    this.partsError.set('');
    this.http
      .get<Part[]>('/api/v1/parts')
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (parts) => {
          this.parts.set(parts);
          this.partsLoading.set(false);
        },
        error: () => {
          this.partsLoading.set(false);
          this.partsError.set('Não foi possível carregar as peças.');
        },
      });
  }
  protected selectPart(): void {
    const part = this.parts().find((p) => p.id === this.partId);
    if (part) {
      this.description = part.name;
      this.unitPrice = part.salePrice;
    }
  }
  private saving(value: boolean): void {
    this.busy.set(value);
    this.busyChanged.emit(value);
  }
  private apply(data: Saved): void {
    this.items.set(data.items);
    this.versionChanged.emit(data.workOrderVersion);
    this.saving(false);
    this.loadParts();
  }
  private fail(error: { status: number; error?: { detail?: string } }): void {
    this.saving(false);
    this.error.set(
      error.status < 500 && typeof error.error?.detail === 'string'
        ? error.error.detail
        : 'Não foi possível confirmar a operação. Tente novamente.',
    );
    // Reload server version after contention, preserving the same item ID for a safe retry.
    if (error.status === 409) {
      const message = this.error();
      this.load();
      this.error.set(message);
    }
  }
  protected add(): void {
    if (
      !this.canEdit() ||
      !this.loaded() ||
      this.busy() ||
      this.blocked() ||
      !this.description.trim() ||
      this.quantity <= 0 ||
      this.unitPrice < 0 ||
      (this.type === 'PART' && !this.partId)
    )
      return;
    const fields = {
      type: this.type,
      partId: this.type === 'PART' ? this.partId : null,
      description: this.description.trim(),
      quantity: this.quantity,
      unitPrice: this.unitPrice,
    };
    const key = JSON.stringify(fields);
    if (this.pendingAdd?.key !== key) this.pendingAdd = { key, id: crypto.randomUUID() };
    this.saving(true);
    this.error.set('');
    this.notice.set('');
    this.http
      .post<Saved>(`/api/v1/work-orders/${this.order().id}/items`, {
        ...fields,
        id: this.pendingAdd!.id,
        version: this.order().version,
      })
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (data) => {
          this.apply(data);
          this.pendingAdd = null;
          this.description = '';
          this.partId = '';
          this.quantity = 1;
          this.unitPrice = 0;
          this.notice.set('Item adicionado à OS.');
        },
        error: (error) => this.fail(error),
      });
  }
  protected remove(item: Item): void {
    if (!this.canEdit() || this.busy() || this.blocked()) return;
    this.saving(true);
    this.error.set('');
    this.notice.set('');
    this.http
      .delete<Saved>(`/api/v1/work-orders/${this.order().id}/items/${item.id}`, {
        params: { version: this.order().version },
      })
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (data) => {
          this.apply(data);
          this.notice.set(
            item.type === 'PART'
              ? 'Peça removida e quantidade devolvida ao estoque.'
              : 'Serviço removido.',
          );
        },
        error: (error) => this.fail(error),
      });
  }
  protected createPart(): void {
    if (this.role !== 'OWNER' || this.busy() || this.blocked()) return;
    this.saving(true);
    this.error.set('');
    this.notice.set('');
    this.http
      .post<Part>('/api/v1/parts', {
        sku: this.sku.trim(),
        name: this.partName.trim(),
        unit: 'UN',
        cost: this.partCost,
        salePrice: this.partPrice,
        minQuantity: 0,
      })
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (part) => {
          this.saving(false);
          this.parts.update((parts) => [...parts, part]);
          this.type = 'PART';
          this.partId = part.id;
          this.selectPart();
          this.sku = '';
          this.partName = '';
          this.notice.set('Peça cadastrada. Adicione o item para registrar o consumo na OS.');
        },
        error: (error) => this.fail(error),
      });
  }
}
