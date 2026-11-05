import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize } from 'rxjs';
import { SessionService } from '../../../../core/auth/data-access/session.service';
interface Equipment {
  id: string;
  customerId: string;
  customerName: string;
  customerDeleted: boolean;
  category: string;
  brand: string;
  model: string;
  serialNumber: string;
  description: string;
}
interface Page {
  content: Equipment[];
  totalElements: number;
  page: number;
  size: number;
}
interface Customer {
  id: string;
  name: string;
}
@Component({
  selector: 'app-equipment',
  imports: [FormsModule, RouterLink],
  templateUrl: './equipment.html',
  styleUrls: ['../customers/customers.css', './equipment.css'],
})
export class EquipmentPanel implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly destroy = inject(DestroyRef);
  private readonly role = inject(SessionService).session()?.role;
  protected readonly canWrite = ['OWNER', 'ATTENDANT'].includes(this.role ?? '');
  protected readonly canDescribe = this.canWrite || this.role === 'TECHNICIAN';
  protected readonly data = signal<Page>({ content: [], totalElements: 0, page: 0, size: 20 });
  protected readonly loading = signal(false);
  protected readonly saving = signal(false);
  protected readonly editing = signal(false);
  protected readonly error = signal('');
  protected readonly notice = signal('');
  protected readonly deleting = signal<Equipment | null>(null);
  protected readonly customers = signal<Customer[]>([]);
  protected readonly customerLoading = signal(false);
  protected readonly customerError = signal('');
  protected readonly customerTotal = signal(0);
  protected customerPage = 0;
  protected customerQuery = '';
  protected selectedCustomer: Customer | null = null;
  protected filterCustomer: string | null = null;
  protected serial = '';
  private appliedSerial = '';
  private request = 0;
  private customerRequest = 0;
  protected id: string | null = null;
  protected category = 'NOTEBOOK';
  protected brand = '';
  protected model = '';
  protected serialNumber = '';
  protected description = '';
  protected readonly categories = [
    { value: 'CELULAR', label: 'Celular' },
    { value: 'NOTEBOOK', label: 'Notebook' },
    { value: 'DESKTOP', label: 'Desktop' },
    { value: 'IMPRESSORA', label: 'Impressora' },
    { value: 'PERIFERICO', label: 'Periférico' },
    { value: 'OUTRO', label: 'Outro' },
  ];
  protected categoryName(value: string): string {
    return this.categories.find((c) => c.value === value)?.label ?? value;
  }
  ngOnInit(): void {
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroy)).subscribe((params) => {
      this.filterCustomer = params.get('customerId');
      this.load(0);
    });
  }
  protected search(): void {
    this.appliedSerial = this.serial.trim();
    this.load(0);
  }
  protected load(page: number): void {
    const request = ++this.request;
    this.loading.set(true);
    this.error.set('');
    const params: Record<string, string | number> = { serial: this.appliedSerial, page, size: 20 };
    if (this.filterCustomer) params['customerId'] = this.filterCustomer;
    this.http.get<Page>('/api/v1/equipment', { params }).subscribe({
      next: (data) => {
        if (request === this.request) {
          this.data.set(data);
          this.loading.set(false);
        }
      },
      error: () => {
        if (request === this.request) {
          this.loading.set(false);
          this.error.set('Não foi possível carregar os equipamentos. Tente buscar novamente.');
        }
      },
    });
  }
  protected edit(value?: Equipment): void {
    this.id = value?.id ?? null;
    this.category = value?.category ?? 'NOTEBOOK';
    this.brand = value?.brand ?? '';
    this.model = value?.model ?? '';
    this.serialNumber = value?.serialNumber ?? '';
    this.description = value?.description ?? '';
    this.selectedCustomer = value
      ? {
          id: value.customerId,
          name: value.customerName + (value.customerDeleted ? ' (excluído)' : ''),
        }
      : null;
    this.error.set('');
    this.notice.set('');
    this.customerQuery = '';
    this.editing.set(true);
    if (this.canWrite) this.findCustomers(0);
  }
  protected findCustomers(page = 0): void {
    const request = ++this.customerRequest;
    this.customerLoading.set(true);
    this.customerError.set('');
    this.customerPage = page;
    this.http
      .get<{ content: Customer[]; totalElements: number }>('/api/v1/customers', {
        params: { q: this.customerQuery.trim(), page, size: 10 },
      })
      .subscribe({
        next: (data) => {
          if (request === this.customerRequest) {
            this.customers.set(data.content);
            this.customerTotal.set(data.totalElements);
            this.customerLoading.set(false);
          }
        },
        error: () => {
          if (request === this.customerRequest) {
            this.customerLoading.set(false);
            this.customerError.set('Não foi possível buscar clientes. Tente novamente.');
          }
        },
      });
  }
  protected save(): void {
    if (this.saving()) return;
    if (this.canWrite && !this.selectedCustomer) {
      this.error.set('Selecione o cliente responsável pelo equipamento.');
      return;
    }
    this.saving.set(true);
    this.error.set('');
    const body = {
      customerId: this.selectedCustomer?.id,
      category: this.category,
      brand: this.brand,
      model: this.model,
      serialNumber: this.serialNumber,
      description: this.description,
    };
    const operation = !this.canWrite
      ? this.http.patch(`/api/v1/equipment/${this.id}/description`, {
          description: this.description,
        })
      : this.id
        ? this.http.put(`/api/v1/equipment/${this.id}`, body)
        : this.http.post('/api/v1/equipment', body);
    operation.pipe(finalize(() => this.saving.set(false))).subscribe({
      next: () => {
        this.editing.set(false);
        this.notice.set('Equipamento salvo.');
        this.load(0);
      },
      error: (error) =>
        this.showError(error, 'Não foi possível salvar. Confira os dados e tente novamente.'),
    });
  }
  protected remove(): void {
    const equipment = this.deleting();
    if (!equipment || this.saving()) return;
    this.saving.set(true);
    this.error.set('');
    this.http
      .delete(`/api/v1/equipment/${equipment.id}`)
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: () => {
          this.deleting.set(null);
          this.notice.set('Equipamento excluído.');
          this.load(
            this.data().content.length === 1 ? Math.max(0, this.data().page - 1) : this.data().page,
          );
        },
        error: (error) => {
          this.deleting.set(null);
          this.showError(error, 'Não foi possível excluir o equipamento.');
        },
      });
  }
  private showError(error: HttpErrorResponse, fallback: string): void {
    this.error.set(
      typeof error.error?.detail === 'string' && error.status < 500 ? error.error.detail : fallback,
    );
  }
}
