import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { CustomerPrivacyPanel } from './customer-privacy';
describe('Customer privacy', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [CustomerPrivacyPanel],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }),
  );
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    vi.useRealTimers();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });
  function setup(anonymized = false) {
    const fixture = TestBed.createComponent(CustomerPrivacyPanel);
    fixture.componentRef.setInput('customerId', 'customer');
    fixture.componentRef.setInput('anonymized', anonymized);
    fixture.detectChanges();
    return {
      fixture,
      component: fixture.componentInstance,
      http: TestBed.inject(HttpTestingController),
      page: fixture.nativeElement as HTMLElement,
    };
  }
  it('requires explicit confirmation before submitting anonymization', () => {
    const { component, http, fixture, page } = setup();
    component['start']();
    fixture.detectChanges();
    component['anonymize']();
    http.expectNone('/api/v1/customers/customer/anonymize');
    expect((page.querySelector('button[type="submit"]') as HTMLButtonElement).disabled).toBe(true);
    component['confirmed'] = true;
    component['anonymize']();
    const request = http.expectOne('/api/v1/customers/customer/anonymize');
    expect(request.request.body).toEqual({ confirmed: true });
    request.flush(null, { status: 204, statusText: 'No Content' });
    fixture.detectChanges();
    expect(page.textContent).toContain('Dados anonimizados');
    expect(page.textContent).not.toContain('Confirmar anonimização');
  });
  it('keeps confirmation open and displays an open OS conflict', () => {
    const { component, http, fixture, page } = setup();
    component['start']();
    component['confirmed'] = true;
    component['anonymize']();
    http
      .expectOne('/api/v1/customers/customer/anonymize')
      .flush({ detail: 'Cliente possui OS aberta.' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(page.querySelector('[role="alert"]')?.textContent).toContain('OS aberta');
    expect(component['confirming']()).toBe(true);
    expect(component['completed']()).toBe(false);
  });
  it('downloads the complete JSON through an authenticated request', () => {
    vi.useFakeTimers();
    const { component, http, page } = setup();
    const create = vi.fn(() => 'blob:test');
    const revoke = vi.fn();
    const originalURL = URL;
    vi.stubGlobal(
      'URL',
      class extends originalURL {
        static override createObjectURL = create;
        static override revokeObjectURL = revoke;
      },
    );
    let filename = '';
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      filename = this.download;
    });
    component['export']();
    const request = http.expectOne('/api/v1/customers/customer/export');
    expect(request.request.responseType).toBe('blob');
    request.flush(new Blob(['{"schemaVersion":1}'], { type: 'application/json' }));
    expect(create).toHaveBeenCalledOnce();
    expect(filename).toBe('cliente-customer.json');
    expect(page.querySelector('a[download]')).toBeNull();
    vi.runAllTimers();
    expect(revoke).toHaveBeenCalledWith('blob:test');
  });
  it('prevents repeat submissions and reports failure without a success message', () => {
    const { component, http } = setup();
    component['start']();
    component['confirmed'] = true;
    component['anonymize']();
    component['anonymize']();
    http
      .expectOne('/api/v1/customers/customer/anonymize')
      .flush({}, { status: 500, statusText: 'Server Error' });
    expect(component['error']()).toContain('Nenhuma alteração foi concluída');
    expect(component['notice']()).toBe('');
    expect(component['completed']()).toBe(false);
  });
  it('keeps export available after anonymization and hides the destructive action', () => {
    const { page, component, http } = setup(true);
    expect(page.textContent).toContain('Cliente anonimizado');
    expect(page.textContent).not.toContain('Anonimizar dados');
    component['confirmed'] = true;
    component['anonymize']();
    http.expectNone('/api/v1/customers/customer/anonymize');
    expect(
      [...page.querySelectorAll('button')].some((button) =>
        button.textContent?.includes('Exportar JSON'),
      ),
    ).toBe(true);
  });
});
