import { signal } from '@angular/core';
import { SessionService } from '../../../../core/auth/data-access/session.service';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of, Subject } from 'rxjs';
import { WorkOrderDetail } from './work-order-detail';
const order = {
  id: 'order',
  number: 42,
  customerId: 'customer',
  equipmentId: 'equipment',
  customerName: 'Ana',
  equipmentLabel: 'Dell Inspiron',
  serialNumber: 'ABC',
  status: 'RECEBIDA',
  reportedIssue: 'Não liga',
  physicalCondition: 'Risco na tampa',
  receivedAccessories: ['Carregador'],
  openedAt: '2026-11-05T12:00:00Z',
  version: 0,
  technicianId: null,
  technicianName: null,
};
describe('Work order details', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [WorkOrderDetail],
      providers: [
        { provide: SessionService, useValue: { session: signal({ role: 'OWNER' }) } },
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { paramMap: of(convertToParamMap({ orderId: 'order' })) },
        },
      ],
    }),
  );
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  it('loads and displays the selected order with a link back to its customer history', () => {
    const fixture = TestBed.createComponent(WorkOrderDetail);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController).expectOne('/api/v1/work-orders/order').flush(order);
    fixture.detectChanges();
    const page = fixture.nativeElement as HTMLElement;
    for (const value of [
      '42',
      'Ana',
      'Dell Inspiron',
      'ABC',
      'Recebida',
      'Não liga',
      'Risco na tampa',
      'Carregador',
    ])
      expect(page.textContent).toContain(value);
    expect(page.querySelector('a')?.getAttribute('href')).toBe(
      '/inicio/clientes/customer/historico',
    );
  });
  it('handles an unavailable order and allows retrying', () => {
    const fixture = TestBed.createComponent(WorkOrderDetail);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/work-orders/order').flush({}, { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('não encontrada');
    fixture.nativeElement.querySelector('button').click();
    http.expectOne('/api/v1/work-orders/order').flush({
      ...order,
      equipmentLabel: '',
      reportedIssue: null,
      physicalCondition: null,
      receivedAccessories: [],
    });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Não informado');
    expect(fixture.nativeElement.textContent).toContain('Nenhum acessório registrado');
  });
  it('cancels a previous request when navigating to another order', () => {
    const params = new Subject<ReturnType<typeof convertToParamMap>>();
    TestBed.overrideProvider(ActivatedRoute, { useValue: { paramMap: params } });
    const fixture = TestBed.createComponent(WorkOrderDetail);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    params.next(convertToParamMap({ orderId: 'first' }));
    const first = http.expectOne('/api/v1/work-orders/first');
    params.next(convertToParamMap({ orderId: 'second' }));
    expect(first.cancelled).toBe(true);
    http.expectOne('/api/v1/work-orders/second').flush({ ...order, number: 43 });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('43');
  });
  it('submits the expected version once and updates the displayed status', () => {
    const fixture = TestBed.createComponent(WorkOrderDetail);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/work-orders/order').flush(order);
    const component = fixture.componentInstance;
    component['target'] = 'CANCELADA';
    component['reason'] = ' Cliente desistiu ';
    component['changeStatus']();
    component['changeStatus']();
    const request = http.expectOne('/api/v1/work-orders/order/status');
    expect(request.request.method).toBe('PATCH');
    expect(request.request.body).toEqual({
      toStatus: 'CANCELADA',
      version: 0,
      reason: 'Cliente desistiu',
      paymentOverride: false,
    });
    request.flush({ ...order, status: 'CANCELADA', version: 1 });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Cancelada');
    expect(fixture.nativeElement.textContent).toContain('Estado da OS atualizado');
    expect(fixture.nativeElement.querySelector('form')).toBeNull();
  });
  it('shows a failed guard without changing the order and reloads after a version conflict', () => {
    const fixture = TestBed.createComponent(WorkOrderDetail);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/work-orders/order').flush(order);
    const component = fixture.componentInstance;
    component['target'] = 'EM_DIAGNOSTICO';
    component['changeStatus']();
    http
      .expectOne('/api/v1/work-orders/order/status')
      .flush(
        { code: 'INVALID_STATE_TRANSITION', detail: 'Atribua um técnico.' },
        { status: 409, statusText: 'Conflict' },
      );
    fixture.detectChanges();
    expect(component['order']()?.status).toBe('RECEBIDA');
    expect(fixture.nativeElement.textContent).toContain('Atribua um técnico');
    component['changeStatus']();
    http
      .expectOne('/api/v1/work-orders/order/status')
      .flush(
        { code: 'WORK_ORDER_VERSION_CONFLICT', detail: 'Atualize os dados.' },
        { status: 409, statusText: 'Conflict' },
      );
    http
      .expectOne('/api/v1/work-orders/order')
      .flush({ ...order, status: 'EM_DIAGNOSTICO', version: 1 });
    expect(component['order']()?.version).toBe(1);
    expect(component['target']).toBe('');
  });
  it('only offers reception actions to attendants and no actions for terminal states', () => {
    TestBed.overrideProvider(SessionService, {
      useValue: { session: signal({ role: 'ATTENDANT' }) },
    });
    const component = TestBed.createComponent(WorkOrderDetail).componentInstance;
    expect(component['targets']('RECEBIDA')).toEqual(['CANCELADA']);
    expect(component['targets']('ENTREGUE')).toEqual([]);
    expect(component['targets']('CANCELADA')).toEqual([]);
  });
  it('loads technicians on selection and uses the assigned version to start diagnosis', () => {
    const fixture = TestBed.createComponent(WorkOrderDetail);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/work-orders/order').flush(order);
    fixture.detectChanges();
    fixture.nativeElement
      .querySelector('select[name="technicianId"]')
      .dispatchEvent(new Event('focus'));
    http
      .expectOne('/api/v1/work-orders/technicians')
      .flush([{ userId: 'technician', fullName: 'João Técnico' }]);
    const component = fixture.componentInstance;
    component['selectedTechnician'] = 'technician';
    component['assignTechnician']();
    component['assignTechnician']();
    const request = http.expectOne('/api/v1/work-orders/order/technician');
    expect(request.request.body).toEqual({ technicianId: 'technician', version: 0 });
    request.flush({
      ...order,
      technicianId: 'technician',
      technicianName: 'João Técnico',
      version: 1,
    });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('João Técnico');
    expect(fixture.nativeElement.textContent).toContain('Técnico atribuído à OS');
    component['target'] = 'EM_DIAGNOSTICO';
    component['changeStatus']();
    const transition = http.expectOne('/api/v1/work-orders/order/status');
    expect(transition.request.body.version).toBe(1);
    transition.flush({
      ...order,
      technicianId: 'technician',
      technicianName: 'João Técnico',
      status: 'EM_DIAGNOSTICO',
      version: 2,
    });
  });
  it('preserves the order and explains an unavailable technician', () => {
    const fixture = TestBed.createComponent(WorkOrderDetail);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/work-orders/order').flush(order);
    const component = fixture.componentInstance;
    component['selectedTechnician'] = 'inactive';
    component['assignTechnician']();
    http
      .expectOne('/api/v1/work-orders/order/technician')
      .flush(
        { detail: 'Técnico ativo não encontrado nesta empresa.' },
        { status: 404, statusText: 'Not Found' },
      );
    fixture.detectChanges();
    expect(component['order']()?.version).toBe(0);
    expect(component['assigning']()).toBe(false);
    expect(fixture.nativeElement.textContent).toContain('Técnico ativo não encontrado');
  });
  it('does not offer assignment controls to technicians', () => {
    TestBed.overrideProvider(SessionService, {
      useValue: { session: signal({ role: 'TECHNICIAN' }) },
    });
    const fixture = TestBed.createComponent(WorkOrderDetail);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/work-orders/order')
      .flush({ ...order, technicianId: 'technician', technicianName: 'João Técnico' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('select[name="technicianId"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('.back-link').getAttribute('href')).toBe(
      '/inicio/ordens',
    );
    expect(fixture.nativeElement.textContent).toContain('João Técnico');
  });
  it('saves diagnosis revisions and uses the updated version to advance to quoting', () => {
    const fixture = TestBed.createComponent(WorkOrderDetail);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http
      .expectOne('/api/v1/work-orders/order')
      .flush({ ...order, status: 'EM_DIAGNOSTICO', version: 2, diagnosis: null });
    const component = fixture.componentInstance;
    component['findings'] = ' Não liga ';
    component['probableCause'] = ' Fonte danificada ';
    component['proposedSolution'] = ' Trocar fonte ';
    component['saveDiagnosis']();
    component['saveDiagnosis']();
    const request = http.expectOne('/api/v1/work-orders/order/diagnosis');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      version: 2,
      findings: 'Não liga',
      probableCause: 'Fonte danificada',
      proposedSolution: 'Trocar fonte',
    });
    const first = {
      id: 'diagnosis-1',
      revision: 1,
      findings: 'Não liga',
      probableCause: 'Fonte danificada',
      proposedSolution: 'Trocar fonte',
      recordedBy: 'owner',
      recordedByName: 'João',
      createdAt: '2026-11-05T12:00:00Z',
    };
    request.flush({ diagnosis: first, workOrderVersion: 3 });
    component['showDiagnosisHistory']();
    http.expectOne('/api/v1/work-orders/order/diagnosis').flush([first]);
    component['findings'] = 'Fonte em curto';
    component['saveDiagnosis']();
    http
      .expectOne('/api/v1/work-orders/order/diagnosis')
      .flush({
        diagnosis: { ...first, id: 'diagnosis-2', revision: 2, findings: 'Fonte em curto' },
        workOrderVersion: 4,
      });
    expect(component['diagnosisHistory']().map((item) => item.revision)).toEqual([2, 1]);
    component['target'] = 'AGUARDANDO_ORCAMENTO';
    component['changeStatus']();
    const transition = http.expectOne('/api/v1/work-orders/order/status');
    expect(transition.request.body.version).toBe(4);
    transition.flush({
      ...order,
      status: 'AGUARDANDO_ORCAMENTO',
      version: 5,
      diagnosis: { ...first, revision: 2, findings: 'Fonte em curto' },
    });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('textarea[name="findings"]')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Fonte em curto');
  });
  it('requires every diagnosis field and preserves drafts when saving fails', () => {
    const fixture = TestBed.createComponent(WorkOrderDetail);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/work-orders/order').flush({ ...order, status: 'EM_DIAGNOSTICO' });
    const component = fixture.componentInstance;
    component['findings'] = 'Achados';
    component['saveDiagnosis']();
    http.expectNone('/api/v1/work-orders/order/diagnosis');
    component['probableCause'] = 'Causa';
    component['proposedSolution'] = 'Solução';
    component['saveDiagnosis']();
    http
      .expectOne('/api/v1/work-orders/order/diagnosis')
      .flush({}, { status: 500, statusText: 'Internal Server Error' });
    fixture.detectChanges();
    expect(component['findings']).toBe('Achados');
    expect(component['order']()?.version).toBe(0);
    expect(component['diagnosing']()).toBe(false);
    expect(fixture.nativeElement.textContent).toContain('Não foi possível salvar o diagnóstico');
  });
  it('shows a read-only diagnosis to attendants and loads its revision history', () => {
    TestBed.overrideProvider(SessionService, {
      useValue: { session: signal({ role: 'ATTENDANT' }) },
    });
    const fixture = TestBed.createComponent(WorkOrderDetail);
    fixture.detectChanges();
    const diagnosis = {
      id: 'diagnosis',
      revision: 1,
      findings: 'Achados',
      probableCause: 'Causa',
      proposedSolution: 'Solução',
      recordedBy: 'technician',
      recordedByName: 'João',
      createdAt: '2026-11-05T12:00:00Z',
    };
    const http = TestBed.inject(HttpTestingController);
    http
      .expectOne('/api/v1/work-orders/order')
      .flush({ ...order, status: 'EM_DIAGNOSTICO', diagnosis });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('textarea[name="findings"]')).toBeNull();
    fixture.componentInstance['showDiagnosisHistory']();
    http.expectOne('/api/v1/work-orders/order/diagnosis').flush([diagnosis]);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Revisão 1');
    expect(fixture.nativeElement.textContent).toContain('Solução');
  });
});
