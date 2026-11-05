import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { WorkOrderAttachmentsPanel } from './work-order-attachments';
const file = {
  id: 'attachment',
  originalName: 'foto.png',
  contentType: 'image/png',
  sizeBytes: 1234,
  createdAt: '2026-11-05T12:00:00Z',
};
describe('Work order attachments', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [WorkOrderAttachmentsPanel],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }),
  );
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  function setup(files: (typeof file)[] = []) {
    const fixture = TestBed.createComponent(WorkOrderAttachmentsPanel);
    fixture.componentRef.setInput('orderId', 'order');
    fixture.detectChanges();
    const component = fixture.componentInstance;
    const http = TestBed.inject(HttpTestingController);
    component['toggle']();
    http.expectOne('/api/v1/work-orders/order/attachments').flush(files);
    fixture.detectChanges();
    return { fixture, component, http, page: fixture.nativeElement as HTMLElement };
  }
  function choose(component: WorkOrderAttachmentsPanel, file: File) {
    component['choose']({ target: { files: [file] } } as unknown as Event);
  }
  it('lists attachment metadata and obtains a temporary URL only on demand', () => {
    const { fixture, component, http, page } = setup([file]);
    expect(page.textContent).toContain('foto.png');
    expect(page.querySelector('a')).toBeNull();
    component['generateDownload'](file);
    http
      .expectOne('/api/v1/attachments/attachment/download-url')
      .flush({
        url: 'https://private.example/file?signature=test',
        expiresAt: new Date(Date.now() + 300000).toISOString(),
      });
    fixture.detectChanges();
    expect(page.querySelector('a')?.getAttribute('href')).toBe(
      'https://private.example/file?signature=test',
    );
    expect(page.querySelector('a')?.getAttribute('rel')).toBe('noopener noreferrer');
    fixture.destroy();
  });
  it('sends multipart with a stable upload ID on an uncertain retry', () => {
    const { fixture, component, http, page } = setup();
    const input = page.querySelector<HTMLInputElement>('input[type=file]')!;
    choose(component, new File(['png bytes'], 'foto.png', { type: 'image/png' }));
    component['upload'](input);
    const first = http.expectOne('/api/v1/work-orders/order/attachments');
    const uploadId = (first.request.body as FormData).get('uploadId');
    expect((first.request.body as FormData).get('file')).toBeInstanceOf(File);
    expect(first.request.headers.has('Content-Type')).toBe(false);
    first.flush({}, { status: 500, statusText: 'Error' });
    component['upload'](input);
    const second = http.expectOne('/api/v1/work-orders/order/attachments');
    expect((second.request.body as FormData).get('uploadId')).toBe(uploadId);
    second.flush(file);
    fixture.detectChanges();
    expect(component['selected']()).toBeNull();
    expect(page.textContent).toContain('Arquivo anexado');
    expect(component['attachments']()).toHaveLength(1);
  });
  it('rejects empty files, unsupported types and files exceeding ten MiB before upload', () => {
    const { component } = setup();
    choose(component, new File([], 'foto.png', { type: 'image/png' }));
    expect(component['selected']()).toBeNull();
    choose(component, new File(['svg'], 'foto.svg', { type: 'image/svg+xml' }));
    expect(component['selected']()).toBeNull();
    choose(
      component,
      new File([new Uint8Array(10 * 1024 * 1024 + 1)], 'foto.png', { type: 'image/png' }),
    );
    expect(component['error']()).toContain('10 MB');
    expect(component['selected']()).toBeNull();
  });
  it('disables upload when the order already has twenty attachments', () => {
    const { page } = setup(
      Array.from({ length: 20 }, (_, index) => ({ ...file, id: `attachment-${index}` })),
    );
    expect(page.querySelector<HTMLInputElement>('input[type=file]')?.disabled).toBe(true);
    expect(page.textContent).toContain('atingiu o limite de 20');
  });
  it('shows server validation errors while retaining the selected file for correction or retry', () => {
    const { fixture, component, http, page } = setup();
    choose(component, new File(['MZ fake image'], 'foto.png', { type: 'image/png' }));
    component['upload'](page.querySelector('input')!);
    http
      .expectOne('/api/v1/work-orders/order/attachments')
      .flush(
        { detail: 'Extensão, MIME e conteúdo devem corresponder.' },
        { status: 422, statusText: 'Unprocessable' },
      );
    fixture.detectChanges();
    expect(page.querySelector('[role="alert"]')?.textContent).toContain('conteúdo');
    expect(component['attachments']()).toHaveLength(0);
    expect(component['selected']()).not.toBeNull();
  });
});
