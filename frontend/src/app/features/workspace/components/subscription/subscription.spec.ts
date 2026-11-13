import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { SubscriptionPanel } from './subscription';
import { SubscriptionAccessState } from '../../../../core/auth/data-access/subscription-access.service';
const trial = {
  access: {
    status: 'TRIALING',
    writable: true,
    accessUntil: '2026-11-23T12:00:00Z',
    trialDaysRemaining: 14,
  },
  trialStartedAt: '2026-11-09T12:00:00Z',
  trialEndsAt: '2026-11-23T12:00:00Z',
  currentPlan: 'STARTER',
  paidFrom: null,
  paidUntil: null,
  cancelAtPeriodEnd: false,
  contract: null,
  activeUsers: 1,
  ordersThisMonth: 4,
  gatewayAvailable: true,
  plans: [
    {
      code: 'STARTER',
      name: 'Starter',
      monthlyPrice: 69,
      maxUsers: 2,
      maxOrders: 100,
      available: true,
    },
  ],
  invoices: [],
};
describe('SubscriptionPanel', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [SubscriptionPanel],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }),
  );
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  function setup(data: object = trial) {
    const fixture = TestBed.createComponent(SubscriptionPanel);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/subscription').flush(data);
    fixture.detectChanges();
    return { fixture, http, page: fixture.nativeElement as HTMLElement };
  }
  function button(page: HTMLElement, label: string): HTMLButtonElement {
    return [...page.querySelectorAll('button')].find((b) => b.textContent?.trim() === label)!;
  }
  it('shows trial, usage and explicit purchase confirmation before sending payment request', () => {
    const { fixture, http, page } = setup();
    expect(page.textContent).toContain('14 dia(s)');
    expect(page.textContent).toContain('4');
    button(page, 'Selecionar Starter').click();
    fixture.detectChanges();
    http.expectNone((r) => r.method === 'POST');
    button(page, 'Continuar com Mercado Pago').click();
    const request = http.expectOne('/api/v1/subscription');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ plan: 'STARTER' });
    expect(request.request.headers.get('Idempotency-Key')).toBeTruthy();
    request.flush({
      ...trial,
      contract: {
        id: 'c1',
        plan: 'STARTER',
        amount: 69,
        cycle: 'MONTHLY',
        state: 'ACTIVE',
        cancelledAt: null,
        checkoutUrl: 'https://www.mercadopago.com.br/subscriptions/checkout?preapproval_id=abc123',
      },
      invoices: [
        {
          id: 'pay_1',
          amount: 69,
          dueDate: '2026-11-10',
          status: 'PENDING',
          url: 'https://www.asaas.com/i/test',
          paidAt: null,
          appliedAt: null,
        },
      ],
    });
    fixture.detectChanges();
    const checkout = page.querySelector<HTMLAnchorElement>('a[href*="mercadopago.com.br"]');
    expect(checkout?.href).toBe(
      'https://www.mercadopago.com.br/subscriptions/checkout?preapproval_id=abc123',
    );
    expect(checkout?.target).toBe('_blank');
    expect(checkout?.rel).toContain('noopener');
    expect(TestBed.inject(SubscriptionAccessState).access()?.status).toBe('TRIALING');
  });
  it('refreshes after the Mercado Pago return without trusting query parameters as payment proof', () => {
    const previousUrl = window.location.href;
    window.history.replaceState({}, '', '?assinatura=retorno');
    try {
      const fixture = TestBed.createComponent(SubscriptionPanel);
      fixture.detectChanges();
      const http = TestBed.inject(HttpTestingController);
      const request = http.expectOne('/api/v1/subscription/refresh');
      expect(request.request.method).toBe('POST');
      request.flush(trial);
      fixture.detectChanges();
      expect(TestBed.inject(SubscriptionAccessState).access()?.status).toBe('TRIALING');
    } finally {
      window.history.replaceState({}, '', previousUrl);
    }
  });
  it('allows selecting a plan after cancellation without a gateway and explains the payment block', () => {
    const { fixture, page, http } = setup({
      ...trial,
      gatewayAvailable: false,
      cancelAtPeriodEnd: true,
    });
    expect(button(page, 'Selecionar Starter').disabled).toBe(false);
    button(page, 'Selecionar Starter').click();
    fixture.detectChanges();
    expect(page.textContent).toContain('Você selecionou o plano Starter');
    expect(button(page, 'Continuar com Mercado Pago').disabled).toBe(true);
    button(page, 'Continuar com Mercado Pago').click();
    http.expectNone((request) => request.method === 'POST');
    button(page, 'Voltar').click();
    fixture.detectChanges();
    expect(page.querySelector('#confirm-plan')).toBeNull();
  });
  it('requires a paid subscription to upgrade a Starter trial', () => {
    const { http, page } = setup({
      ...trial,
      gatewayAvailable: false,
      plans: [...trial.plans, { ...trial.plans[0], code: 'PROFESSIONAL', name: 'Professional' }],
    });
    expect(button(page, 'Testar Professional')).toBeUndefined();
    expect(button(page, 'Selecionar Professional')).toBeDefined();
    http.expectNone('/api/v1/subscription/plan');
  });
  it('allows selecting another paid plan and explains replacement billing', () => {
    const { fixture, page } = setup({
      ...trial,
      paidUntil: '2026-12-10T12:00:00Z',
      access: { ...trial.access, status: 'ACTIVE' },
      contract: { id: 'c1', plan: 'STARTER', state: 'ACTIVE', amount: 69, cycle: 'MONTHLY' },
      plans: [...trial.plans, { ...trial.plans[0], code: 'PROFESSIONAL', name: 'Professional' }],
    });
    expect(button(page, 'Selecionar Professional').disabled).toBe(false);
    button(page, 'Selecionar Professional').click();
    fixture.detectChanges();
    expect(page.textContent).toContain('A recorrência anterior será cancelada');
    expect(page.textContent).toContain('Não há cálculo proporcional');
  });
  it('allows returning to the pending contract plan after selecting another plan', () => {
    const { fixture, http, page } = setup({
      ...trial,
      currentPlan: 'PROFESSIONAL',
      access: { ...trial.access, status: 'PAYMENT_REQUIRED', writable: false },
      contract: {
        id: 'c1',
        plan: 'PROFESSIONAL',
        state: 'CREATING',
        amount: 119.9,
        cycle: 'MONTHLY',
      },
      plans: [
        ...trial.plans,
        { ...trial.plans[0], code: 'PROFESSIONAL', name: 'Professional', monthlyPrice: 119.9 },
        { ...trial.plans[0], code: 'BUSINESS', name: 'Business', monthlyPrice: 199.9 },
      ],
    });
    button(page, 'Selecionar Business').click();
    fixture.detectChanges();
    expect(page.querySelector('.confirmation')?.textContent).toContain('Business por');
    expect(button(page, 'Selecionar Professional').disabled).toBe(false);
    button(page, 'Selecionar Professional').click();
    fixture.detectChanges();
    expect(page.querySelector('.confirmation')?.textContent).toContain('Professional por');
    expect(page.querySelector('.confirmation')?.textContent).not.toContain(
      'A recorrência anterior será cancelada',
    );
    http.expectNone((request) => request.method === 'POST');
    button(page, 'Continuar com Mercado Pago').click();
    const request = http.expectOne('/api/v1/subscription');
    expect(request.request.body).toEqual({ plan: 'PROFESSIONAL' });
    request.flush(trial);
  });
  it('allows returning to the existing checkout plan without replacing its contract', () => {
    const { fixture, http, page } = setup({
      ...trial,
      access: { ...trial.access, status: 'PAYMENT_REQUIRED', writable: false },
      contract: {
        id: 'c1',
        plan: 'STARTER',
        state: 'ACTIVE',
        amount: 69,
        cycle: 'MONTHLY',
        checkoutUrl: 'https://www.mercadopago.com.br/subscriptions/checkout?preapproval_id=abc123',
      },
      plans: [...trial.plans, { ...trial.plans[0], code: 'BUSINESS', name: 'Business' }],
    });
    button(page, 'Selecionar Business').click();
    fixture.detectChanges();
    button(page, 'Selecionar Starter').click();
    fixture.detectChanges();
    expect(page.querySelector('.confirmation')?.textContent).toContain('Starter por');
    expect(page.querySelector<HTMLAnchorElement>('a[href*="mercadopago.com.br"]')?.href).toContain(
      'preapproval_id=abc123',
    );
    http.expectNone((request) => request.method === 'POST');
  });
  it('requires cancellation confirmation and preserves read access', () => {
    const { fixture, http, page } = setup();
    button(page, 'Cancelar renovação').click();
    fixture.detectChanges();
    http.expectNone('/api/v1/subscription/cancel');
    button(page, 'Confirmar cancelamento').click();
    http.expectOne('/api/v1/subscription/cancel').flush({ ...trial, cancelAtPeriodEnd: true });
    fixture.detectChanges();
    expect(page.textContent).toContain('Renovação cancelada');
    expect(TestBed.inject(SubscriptionAccessState).restricted()).toBe(false);
  });
  it('keeps a useful error after reconciling a failed purchase', () => {
    const { fixture, http, page } = setup();
    button(page, 'Selecionar Starter').click();
    fixture.detectChanges();
    button(page, 'Continuar com Mercado Pago').click();
    http
      .expectOne('/api/v1/subscription')
      .flush({ detail: 'Contratação em conciliação.' }, { status: 502, statusText: 'Bad Gateway' });
    http.expectOne('/api/v1/subscription').flush(trial);
    fixture.detectChanges();
    expect(page.querySelector('[role="alert"]')?.textContent).toContain(
      'Contratação em conciliação.',
    );
  });
  it('checks the pending payment on return from checkout and stops after activation', () => {
    const pending = {
      ...trial,
      access: { ...trial.access, status: 'PAYMENT_REQUIRED', writable: false },
      contract: { id: 'c1', plan: 'STARTER', state: 'ACTIVE', amount: 69, cycle: 'MONTHLY' },
    };
    const { fixture, http, page } = setup(pending);
    window.dispatchEvent(new Event('focus'));
    const request = http.expectOne('/api/v1/subscription/refresh');
    expect(request.request.method).toBe('POST');
    window.dispatchEvent(new Event('focus'));
    http.expectNone('/api/v1/subscription/refresh');
    request.flush({ ...pending, access: { ...trial.access, status: 'ACTIVE', writable: true } });
    fixture.detectChanges();
    expect(TestBed.inject(SubscriptionAccessState).restricted()).toBe(false);
    expect(page.textContent).toContain('Pagamento confirmado. Seu acesso foi liberado.');
    window.dispatchEvent(new Event('focus'));
    http.expectNone('/api/v1/subscription/refresh');
  });
  it('keeps the current plan choice when a background payment check fails', () => {
    const { fixture, http, page } = setup({
      ...trial,
      access: { ...trial.access, status: 'PAYMENT_REQUIRED', writable: false },
      contract: { id: 'c1', plan: 'STARTER', state: 'ACTIVE', amount: 69, cycle: 'MONTHLY' },
    });
    button(page, 'Selecionar Starter').click();
    fixture.detectChanges();
    window.dispatchEvent(new Event('focus'));
    http
      .expectOne('/api/v1/subscription/refresh')
      .flush({}, { status: 502, statusText: 'Bad Gateway' });
    fixture.detectChanges();
    expect(page.querySelector('#confirm-plan')).not.toBeNull();
    expect(button(page, 'Continuar com Mercado Pago').disabled).toBe(false);
    expect(TestBed.inject(SubscriptionAccessState).restricted()).toBe(true);
  });
  it('refreshes paid access without logging in again', () => {
    const { fixture, http, page } = setup({
      ...trial,
      access: { ...trial.access, status: 'EXPIRED', writable: false },
    });
    expect(TestBed.inject(SubscriptionAccessState).restricted()).toBe(true);
    button(page, 'Atualizar pagamentos').click();
    http
      .expectOne('/api/v1/subscription/refresh')
      .flush({ ...trial, access: { ...trial.access, status: 'ACTIVE', writable: true } });
    fixture.detectChanges();
    expect(TestBed.inject(SubscriptionAccessState).restricted()).toBe(false);
    expect(page.textContent).toContain('Ativa');
  });
  it('lets an expired trial remain read-only without contracting and keeps export available', () => {
    const { fixture, http, page } = setup({
      ...trial,
      access: { ...trial.access, status: 'EXPIRED', writable: false },
    });
    button(page, 'Não contratar. Manter consulta e exportação').click();
    const request = http.expectOne('/api/v1/subscription/cancel');
    expect(request.request.method).toBe('POST');
    request.flush({
      ...trial,
      cancelAtPeriodEnd: true,
      access: { ...trial.access, status: 'CANCELED', writable: false },
    });
    fixture.detectChanges();
    expect(TestBed.inject(SubscriptionAccessState).restricted()).toBe(true);
    expect(button(page, 'Exportar planilhas CSV').disabled).toBe(false);
    expect(button(page, 'Selecionar Starter').disabled).toBe(false);
  });
});
