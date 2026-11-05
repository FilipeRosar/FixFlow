import { Component, inject, signal, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { finalize } from 'rxjs';
import { SessionService } from '../../../../core/auth/data-access/session.service';

interface Contact {
  type: string;
  value: string;
  label: string;
}
interface Customer {
  id: string;
  name: string;
  document: string | null;
  contacts: Contact[];
}
interface CustomerPage {
  content: Customer[];
  totalElements: number;
  page: number;
  size: number;
}
@Component({
  selector: 'app-customers',
  imports: [FormsModule],
  templateUrl: './customers.html',
  styleUrl: './customers.css',
})
export class CustomersPanel implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly sessions = inject(SessionService);
  protected readonly canWrite = ['OWNER', 'ATTENDANT'].includes(
    this.sessions.session()?.role ?? '',
  );
  protected readonly data = signal<CustomerPage>({
    content: [],
    totalElements: 0,
    page: 0,
    size: 20,
  });
  protected readonly loading = signal(false);
  protected readonly saving = signal(false);
  protected readonly error = signal('');
  protected readonly notice = signal('');
  protected readonly editing = signal(false);
  protected readonly deleting = signal<Customer | null>(null);
  protected query = '';
  protected pageSize = 20;
  private appliedQuery = '';
  private request = 0;
  protected id: string | null = null;
  protected name = '';
  protected document = '';
  protected contacts: Contact[] = [];
  protected initials(name: string): string {
    const words = name.trim().split(/\s+/);
    return (
      (words[0]?.[0] ?? '') + (words.length > 1 ? words[words.length - 1][0] : '')
    ).toLocaleUpperCase('pt-BR');
  }
  protected formatDocument(value: string | null): string {
    if (!value) return 'Não informado';
    if (value.length === 11) return value.replace(/(\d{3})(\d{3})(\d{3})(\d{2})/, '$1.$2.$3-$4');
    if (value.length === 14)
      return value.replace(/(\d{2})(\d{3})(\d{3})(\d{4})(\d{2})/, '$1.$2.$3/$4-$5');
    return value;
  }
  ngOnInit(): void {
    this.load(0);
  }
  protected search(): void {
    this.appliedQuery = this.query.trim();
    this.load(0);
  }
  protected load(page: number): void {
    const request = ++this.request;
    this.loading.set(true);
    this.error.set('');
    this.http
      .get<CustomerPage>('/api/v1/customers', {
        params: { q: this.appliedQuery, page, size: this.pageSize },
      })
      .subscribe({
        next: (data) => {
          if (request === this.request) {
            this.data.set(data);
            this.loading.set(false);
          }
        },
        error: () => {
          if (request === this.request) {
            this.loading.set(false);
            this.error.set('Não foi possível carregar os clientes. Tente buscar novamente.');
          }
        },
      });
  }
  protected edit(customer?: Customer): void {
    this.id = customer?.id ?? null;
    this.name = customer?.name ?? '';
    this.document = customer?.document ?? '';
    this.contacts = customer?.contacts.map((c) => ({ ...c })) ?? [];
    this.error.set('');
    this.notice.set('');
    this.editing.set(true);
  }
  protected addContact(): void {
    if (this.contacts.length < 20) this.contacts.push({ type: 'PHONE', value: '', label: '' });
  }
  protected save(): void {
    if (this.saving()) return;
    this.saving.set(true);
    this.error.set('');
    const body = { name: this.name, document: this.document, contacts: this.contacts };
    const operation = this.id
      ? this.http.put(`/api/v1/customers/${this.id}`, body)
      : this.http.post('/api/v1/customers', body);
    operation.pipe(finalize(() => this.saving.set(false))).subscribe({
      next: () => {
        this.editing.set(false);
        this.notice.set('Cliente salvo.');
        this.load(this.data().page);
      },
      error: (error) =>
        this.showError(error, 'Não foi possível salvar. Confira os dados e tente novamente.'),
    });
  }
  protected remove(): void {
    const customer = this.deleting();
    if (!customer || this.saving()) return;
    this.saving.set(true);
    this.error.set('');
    this.http
      .delete(`/api/v1/customers/${customer.id}`)
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: () => {
          this.deleting.set(null);
          this.notice.set('Cliente excluído.');
          this.load(
            this.data().content.length === 1 ? Math.max(0, this.data().page - 1) : this.data().page,
          );
        },
        error: (error) => {
          this.deleting.set(null);
          this.showError(error, 'Não foi possível excluir o cliente.');
        },
      });
  }
  private showError(error: HttpErrorResponse, fallback: string): void {
    this.error.set(
      typeof error.error?.detail === 'string' && error.status < 500 ? error.error.detail : fallback,
    );
  }
}
