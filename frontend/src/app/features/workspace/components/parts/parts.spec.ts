import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { PartsPanel } from './parts';
import { SessionService } from '../../../../core/auth/data-access/session.service';
const part = {
  id: 'p1',
  sku: 'P1',
  name: 'Fonte',
  unit: 'UN',
  cost: 10,
  salePrice: 20,
  minQuantity: 2,
  quantityOnHand: 5,
  version: 3,
};
describe('Parts catalog', () => {
  let http: HttpTestingController;
  function setup(role = 'OWNER') {
    TestBed.configureTestingModule({
      imports: [PartsPanel],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: SessionService, useValue: { session: signal({ role }) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(PartsPanel);
    http.expectOne('/api/v1/parts').flush([part]);
    http.expectOne('/api/v1/parts/reconciliation-alerts').flush([]);
    fixture.detectChanges();
    return fixture;
  }
  afterEach(() => http.verify());
  it('edits only catalog fields with the current version and preserves conflicts', () => {
    const fixture = setup();
    fixture.componentInstance['edit'](part);
    fixture.componentInstance['save']();
    const request = http.expectOne('/api/v1/parts/p1');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({
      sku: 'P1',
      name: 'Fonte',
      unit: 'UN',
      cost: 10,
      salePrice: 20,
      minQuantity: 2,
      version: 3,
    });
    request.flush({}, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('peça alterada');
    expect(fixture.componentInstance['editing']).toEqual(part);
  });
  it('confirms deletion with the version and refreshes the catalog', () => {
    const fixture = setup();
    fixture.componentInstance['deleting'].set(part);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('O histórico será preservado');
    fixture.componentInstance['remove']();
    const request = http.expectOne('/api/v1/parts/p1?version=3');
    expect(request.request.method).toBe('DELETE');
    request.flush(null);
    http.expectOne('/api/v1/parts').flush([]);
    http.expectOne('/api/v1/parts/reconciliation-alerts').flush([]);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Nenhuma peça cadastrada');
  });
  it('posts entries with cost and version and refreshes the balance', () => {
    const fixture = setup();
    fixture.componentInstance['startMovement'](part);
    fixture.componentInstance['movement'].quantity = 3;
    fixture.componentInstance['movement'].unitCost = 20;
    fixture.componentInstance['saveMovement']();
    const request = http.expectOne('/api/v1/parts/p1/entries');
    expect(request.request.body).toEqual({ quantity: 3, unitCost: 20, version: 3 });
    request.flush({ ...part, quantityOnHand: 8 });
    http.expectOne('/api/v1/parts').flush([{ ...part, quantityOnHand: 8 }]);
    http.expectOne('/api/v1/parts/reconciliation-alerts').flush([]);
    expect(fixture.componentInstance['moving']).toBeNull();
  });
  it('requires a reason for adjustments and sends the signed quantity', () => {
    const fixture = setup();
    fixture.componentInstance['startMovement'](part);
    fixture.componentInstance['movement'] = {
      type: 'ADJUSTMENT',
      quantity: -1,
      unitCost: 0,
      reason: ' ',
    };
    fixture.componentInstance['saveMovement']();
    http.expectNone('/api/v1/parts/p1/adjustments');
    fixture.componentInstance['movement'].reason = 'Contagem física';
    fixture.componentInstance['saveMovement']();
    const request = http.expectOne('/api/v1/parts/p1/adjustments');
    expect(request.request.body).toEqual({ quantity: -1, reason: 'Contagem física', version: 3 });
    request.flush({}, { status: 409, statusText: 'Conflict' });
    expect(fixture.componentInstance['moving']).toEqual(part);
  });
  it('alerts at equality and filters out parts above the minimum', () => {
    const fixture = setup();
    fixture.componentInstance['parts'].set([
      part,
      { ...part, id: 'p2', name: 'Tela', quantityOnHand: 2 },
      { ...part, id: 'p3', name: 'Bateria', quantityOnHand: 1 },
    ]);
    fixture.detectChanges();
    expect(fixture.componentInstance['belowMinimum']().length).toBe(2);
    expect(fixture.nativeElement.querySelector('.stock-alert').textContent).toContain('2 peças');
    expect(fixture.nativeElement.querySelectorAll('.stock.low').length).toBe(2);
    fixture.nativeElement.querySelector('.stock-alert button').click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('tbody tr').length).toBe(2);
    expect(fixture.componentInstance['visibleParts']().map((item) => item.name)).toEqual([
      'Tela',
      'Bateria',
    ]);
    fixture.componentInstance['parts'].set([{ ...part, id: 'p2', quantityOnHand: 3 }]);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.stock-alert')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain(
      'Nenhuma peça com saldo no mínimo ou abaixo.',
    );
  });
  it('shows persisted reconciliation discrepancies and clears them after the next check', () => {
    const fixture = setup();
    fixture.componentInstance['load']();
    http.expectOne('/api/v1/parts').flush([part]);
    http
      .expectOne('/api/v1/parts/reconciliation-alerts')
      .flush([
        {
          id: 'a1',
          partId: part.id,
          name: part.name,
          sku: part.sku,
          quantityOnHand: 5,
          movementQuantity: 4,
        },
      ]);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.reconciliation-alert').textContent).toContain(
      'Divergência no estoque',
    );
    expect(fixture.nativeElement.querySelector('.reconciliation-alert').textContent).toContain(
      'Soma das movimentações: 4',
    );
    fixture.componentInstance['load']();
    http.expectOne('/api/v1/parts').flush([part]);
    http.expectOne('/api/v1/parts/reconciliation-alerts').flush([]);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.reconciliation-alert')).toBeNull();
  });
  it('shows balances without write controls for attendants', () => {
    const fixture = setup('ATTENDANT');
    expect(fixture.nativeElement.querySelector('form')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Fonte');
    expect(fixture.nativeElement.textContent).not.toContain('Excluir Fonte');
  });
});
