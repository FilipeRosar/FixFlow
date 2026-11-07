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
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Nenhuma peça cadastrada');
  });
  it('shows balances without write controls for attendants', () => {
    const fixture = setup('ATTENDANT');
    expect(fixture.nativeElement.querySelector('form')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Fonte');
    expect(fixture.nativeElement.textContent).not.toContain('Excluir Fonte');
  });
});
