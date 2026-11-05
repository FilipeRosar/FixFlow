import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, Subject, Subscription, switchMap, timer } from 'rxjs';
import { SessionService } from '../../../../core/auth/data-access/session.service';
interface Customer {
  id: string;
  name: string;
}
interface Equipment {
  id: string;
  brand: string;
  model: string;
  serialNumber: string;
}
interface Page<T> {
  content: T[];
  totalElements: number;
}
@Component({
  selector: 'app-work-orders',
  imports: [FormsModule, RouterLink],
  templateUrl: './work-orders.html',
  styleUrls: ['../customers/customers.css', './work-orders.css'],
})
export class WorkOrdersPanel implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly destroy = inject(DestroyRef);
  protected readonly canCreate = ['OWNER', 'ATTENDANT'].includes(
    inject(SessionService).session()?.role ?? '',
  );
  protected readonly customers = signal<Customer[]>([]);
  protected readonly equipment = signal<Equipment[]>([]);
  protected readonly loading = signal(false);
  protected readonly saving = signal(false);
  protected readonly error = signal('');
  protected readonly result = signal<{ number: number; status: string } | null>(null);
  protected query = '';
  protected customerId = '';
  protected equipmentId = '';
  protected serial = '';
  protected issue = '';
  protected accessories = '';
  protected condition = '';
  protected customerPage = 0;
  protected customerTotal = signal(0);
  protected equipmentPage = 0;
  protected equipmentTotal = signal(0);
  private request = 0;
  private readonly customerQueries = new Subject<void>();
  private customerSearch?: Subscription;
  protected readonly customersSearched = signal(false);
  constructor() {
    this.customerQueries
      .pipe(
        switchMap(() => timer(250)),
        takeUntilDestroyed(this.destroy),
      )
      .subscribe(() => this.findCustomers());
  }
  ngOnInit(): void {
    if (this.canCreate && !this.result()) this.findCustomers();
  }
  protected customerQueryChanged(): void {
    this.customerSearch?.unsubscribe();
    this.customers.set([]);
    this.customerTotal.set(0);
    this.customersSearched.set(false);
    this.customerPage = 0;
    this.customerId = '';
    this.selectCustomer();
    this.loading.set(true);
    this.customerQueries.next();
  }
  protected findCustomers(page = 0): void {
    this.customerSearch?.unsubscribe();
    this.loading.set(true);
    this.error.set('');
    this.customerPage = page;
    this.customerSearch = this.http
      .get<Page<Customer>>('/api/v1/customers', {
        params: { q: this.query.trim(), page, size: 10 },
      })
      .pipe(
        takeUntilDestroyed(this.destroy),
        finalize(() => this.loading.set(false)),
      )
      .subscribe({
        next: (data) => {
          this.customers.set(data.content);
          this.customerTotal.set(data.totalElements);
          this.customersSearched.set(true);
        },
        error: () => this.error.set('Não foi possível buscar os clientes. Tente novamente.'),
      });
  }
  protected selectCustomer(): void {
    this.equipmentId = '';
    this.equipment.set([]);
    this.equipmentTotal.set(0);
    this.serial = '';
    this.findEquipment();
  }
  protected findEquipment(page = 0): void {
    const request = ++this.request;
    this.equipmentId = '';
    this.equipment.set([]);
    this.equipmentTotal.set(0);
    if (!this.customerId) return;
    this.equipmentPage = page;
    this.error.set('');
    this.http
      .get<Page<Equipment>>(`/api/v1/customers/${this.customerId}/equipment`, {
        params: { serial: this.serial.trim(), page, size: 10 },
      })
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (data) => {
          if (request === this.request) {
            this.equipment.set(data.content);
            this.equipmentTotal.set(data.totalElements);
          }
        },
        error: () => {
          if (request === this.request)
            this.error.set('Não foi possível buscar os equipamentos. Tente novamente.');
        },
      });
  }
  protected open(): void {
    if (
      this.saving() ||
      !this.canCreate ||
      !this.customerId ||
      !this.equipmentId ||
      !this.issue.trim() ||
      !this.condition.trim()
    )
      return;
    this.saving.set(true);
    this.error.set('');
    this.http
      .post<{ number: number; status: string }>('/api/v1/work-orders', {
        customerId: this.customerId,
        equipmentId: this.equipmentId,
        reportedIssue: this.issue.trim(),
        receivedAccessories: this.accessories
          .split('\n')
          .map((value) => value.trim())
          .filter(Boolean),
        physicalCondition: this.condition.trim(),
      })
      .pipe(
        takeUntilDestroyed(this.destroy),
        finalize(() => this.saving.set(false)),
      )
      .subscribe({
        next: (result) => this.result.set(result),
        error: (error) =>
          this.error.set(
            error.error?.code === 'PLAN_LIMIT_REACHED'
              ? 'Limite de OS do plano atingido.'
              : error.status === 404
                ? 'Cliente ou equipamento indisponível. Selecione novamente.'
                : 'Não foi possível abrir a OS. Confira os dados e tente novamente.',
          ),
      });
  }
  protected another(): void {
    this.result.set(null);
    this.issue = '';
    this.accessories = '';
    this.condition = '';
    this.customerId = '';
    this.equipmentId = '';
    this.equipment.set([]);
    this.customers.set([]);
    this.query = '';
    this.findCustomers();
  }
}
