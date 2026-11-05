import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { CompanySettingsPanel } from './company-settings';

const defaults = {
  logoDataUrl: null,
  warrantyTerms: '',
  quoteValidityDays: 7,
  allowNegativeStock: false,
};
describe('Company settings', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [CompanySettingsPanel],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }),
  );
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  async function setup(settings = defaults) {
    const fixture = TestBed.createComponent(CompanySettingsPanel);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController).expectOne('/api/v1/tenant').flush(settings);
    fixture.detectChanges();
    await fixture.whenStable();
    return {
      fixture,
      page: fixture.nativeElement as HTMLElement,
      http: TestBed.inject(HttpTestingController),
    };
  }
  it('loads persisted preferences and saves edited rules', async () => {
    const { fixture, page, http } = await setup();
    const saved = vi.fn();
    fixture.componentInstance.saved.subscribe(saved);
    const days = page.querySelector<HTMLInputElement>('#quote-validity')!;
    days.value = '14';
    days.dispatchEvent(new Event('input'));
    const terms = page.querySelector<HTMLTextAreaElement>('#warranty-terms')!;
    terms.value = 'Garantia do serviço.';
    terms.dispatchEvent(new Event('input'));
    page.querySelector<HTMLInputElement>('#negative-stock')!.click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(page.querySelector('.stock-note')?.textContent).toContain('abaixo de zero');
    page.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    const request = http.expectOne('/api/v1/tenant');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({
      ...defaults,
      quoteValidityDays: 14,
      warrantyTerms: 'Garantia do serviço.',
      allowNegativeStock: true,
    });
    request.flush(request.request.body);
    fixture.detectChanges();
    expect(saved).toHaveBeenCalledWith(request.request.body);
    expect(page.querySelector('[role="status"]')?.textContent).toContain('Configurações salvas');
  });
  it('keeps unsaved changes after server errors and lets the owner discard them', async () => {
    const { fixture, page, http } = await setup();
    const days = page.querySelector<HTMLInputElement>('#quote-validity')!;
    days.value = '21';
    days.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    await fixture.whenStable();
    page.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    http.expectOne('/api/v1/tenant').flush({}, { status: 500, statusText: 'Error' });
    fixture.detectChanges();
    expect(days.value).toBe('21');
    expect(page.querySelector('[role="status"]')?.textContent).toContain('Não foi possível salvar');
    [...page.querySelectorAll<HTMLButtonElement>('button')]
      .find((button) => button.textContent?.includes('Descartar'))!
      .click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(days.value).toBe('7');
  });
  it('blocks invalid validity periods and rejects unsafe logo types before uploading', async () => {
    const { fixture, page } = await setup();
    const days = page.querySelector<HTMLInputElement>('#quote-validity')!;
    days.value = '0';
    days.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(page.querySelector<HTMLButtonElement>('button[type="submit"]')!.disabled).toBe(true);
    const input = page.querySelector<HTMLInputElement>('input[type="file"]')!;
    Object.defineProperty(input, 'files', {
      value: [new File(['<svg/>'], 'logo.svg', { type: 'image/svg+xml' })],
    });
    input.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(page.querySelector('[role="status"]')?.textContent).toContain('PNG ou JPEG');
    expect(page.querySelector('.logo-preview img')).toBeNull();
  });
  it('recovers from load errors without allowing an empty configuration to overwrite saved data', async () => {
    const fixture = TestBed.createComponent(CompanySettingsPanel);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/tenant').flush({}, { status: 500, statusText: 'Error' });
    fixture.detectChanges();
    const page = fixture.nativeElement as HTMLElement;
    expect(page.querySelector('form')).toBeNull();
    page.querySelector<HTMLButtonElement>('button')!.click();
    http.expectOne('/api/v1/tenant').flush({ ...defaults, quoteValidityDays: 30 });
    fixture.detectChanges();
    await fixture.whenStable();
    expect(page.querySelector<HTMLInputElement>('#quote-validity')!.value).toBe('30');
  });
});
