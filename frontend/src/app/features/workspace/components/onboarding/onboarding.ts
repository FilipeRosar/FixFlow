import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CustomersPanel } from '../customers/customers';
import { EquipmentPanel } from '../equipment/equipment';
import { WorkOrdersPanel } from '../work-orders/work-orders';
interface Profile {
  companyName: string;
  document: string;
  phone: string;
  address: {
    street: string;
    number: string;
    complement: string;
    neighborhood: string;
    city: string;
    state: string;
    postalCode: string;
  };
}
interface View {
  profileCompleted: boolean;
  completed: boolean;
  profile: Profile;
  customers: number;
  equipment: number;
  firstOrderId: string | null;
}
@Component({
  selector: 'app-onboarding',
  imports: [FormsModule, CustomersPanel, EquipmentPanel, WorkOrdersPanel],
  templateUrl: './onboarding.html',
  styleUrls: ['../parts/parts.css', './onboarding.css'],
})
export class OnboardingPanel implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly destroy = inject(DestroyRef);
  protected readonly step = signal(0);
  protected readonly view = signal<View | null>(null);
  protected readonly loading = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal('');
  protected readonly notice = signal('');
  protected profile: Profile = {
    companyName: '',
    document: '',
    phone: '',
    address: {
      street: '',
      number: '',
      complement: '',
      neighborhood: '',
      city: '',
      state: '',
      postalCode: '',
    },
  };
  protected readonly steps = [
    'Sua assistência',
    'Primeiro cliente',
    'Equipamento',
    'Primeiro atendimento',
  ];
  ngOnInit(): void {
    this.load();
  }
  protected load(target?: number): void {
    if (this.loading() || this.busy()) return;
    this.loading.set(true);
    this.error.set('');
    this.http
      .get<View>('/api/v1/onboarding')
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (view) => {
          this.view.set(view);
          this.loading.set(false);
          if (target !== undefined) {
            if (
              (target === 2 && !view.customers) ||
              (target === 3 && !view.equipment) ||
              (target === 4 && !view.firstOrderId)
            ) {
              this.error.set('Conclua o cadastro desta etapa antes de continuar.');
              return;
            }
            this.step.set(target);
            return;
          }
          const p = view.profile;
          this.profile = {
            companyName: p.companyName ?? '',
            document: p.document ?? '',
            phone: p.phone ?? '',
            address: {
              street: p.address.street ?? '',
              number: p.address.number ?? '',
              complement: p.address.complement ?? '',
              neighborhood: p.address.neighborhood ?? '',
              city: p.address.city ?? '',
              state: p.address.state ?? '',
              postalCode: p.address.postalCode ?? '',
            },
          };
          this.step.set(
            view.firstOrderId
              ? 4
              : !view.profileCompleted
                ? 0
                : !view.customers
                  ? 1
                  : !view.equipment
                    ? 2
                    : 3,
          );
        },
        error: () => {
          this.loading.set(false);
          this.error.set('Não foi possível carregar a configuração. Tente novamente.');
        },
      });
  }
  protected save(): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    this.profile.document = this.profile.document.replace(/\D/g, '');
    this.profile.phone = this.profile.phone.replace(/\D/g, '');
    this.http
      .put<View>('/api/v1/onboarding', this.profile)
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (view) => {
          this.view.set(view);
          this.busy.set(false);
          this.step.set(1);
          this.notice.set('Dados da assistência salvos. Agora cadastre seu primeiro cliente.');
        },
        error: (error) => {
          this.busy.set(false);
          this.error.set(
            error.error?.detail || 'Confira os dados da assistência e tente novamente.',
          );
        },
      });
  }
  protected lookupPostalCode(): void {
    const code = this.profile.address.postalCode.replace(/\D/g, '');
    if (code.length !== 8 || this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    const before = { ...this.profile.address };
    this.http
      .get<Partial<Profile['address']>>(`/api/enderecos/cep/${code}`)
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (address) => {
          this.busy.set(false);
          if (this.profile.address.postalCode !== before.postalCode) return;
          for (const field of ['street', 'neighborhood', 'city', 'state'] as const)
            if (this.profile.address[field] === before[field])
              this.profile.address[field] = address[field] ?? '';
          this.notice.set('Endereço localizado. Confira e informe o número.');
        },
        error: () => {
          this.busy.set(false);
          this.error.set('Não foi possível consultar o CEP. Preencha o endereço manualmente.');
        },
      });
  }
  protected complete(): void {
    if (this.busy() || !this.view()?.firstOrderId) return;
    this.busy.set(true);
    this.error.set('');
    this.http
      .post<View>('/api/v1/onboarding/complete', {})
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (view) => {
          this.busy.set(false);
          void this.router.navigate(['/inicio/ordens', view.firstOrderId]);
        },
        error: (error) => {
          this.busy.set(false);
          this.error.set(error.error?.detail || 'Não foi possível concluir. Tente novamente.');
        },
      });
  }
}
