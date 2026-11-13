import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Home } from './home';

describe('Informative home', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({ imports: [Home], providers: [provideRouter([])] }),
  );

  it('links each navigation item to an existing section', () => {
    const fixture = TestBed.createComponent(Home);
    fixture.detectChanges();
    const page = fixture.nativeElement as HTMLElement;
    for (const link of page.querySelectorAll<HTMLAnchorElement>('a[href^="#"]')) {
      expect(page.querySelector(link.getAttribute('href')!)).not.toBeNull();
    }
    expect(page.querySelector<HTMLAnchorElement>('a.button')?.getAttribute('href')).toBe(
      '/#planos',
    );
  });

  it('offers a free Starter trial and paid Professional and Business signups', () => {
    const fixture = TestBed.createComponent(Home);
    fixture.detectChanges();
    const cards = fixture.nativeElement.querySelectorAll('.public-plans article') as NodeListOf<HTMLElement>;
    expect(cards[0].querySelector('a')?.textContent).toContain('Testar Starter grátis');
    expect(cards[1].querySelector('a')?.textContent).toContain('Contratar Professional');
    expect(cards[1].querySelector('a')?.getAttribute('href')).toBe('/cadastro?plano=PROFESSIONAL');
    expect(cards[2].querySelector('a')?.textContent).toContain('Contratar Business');
    expect(cards[2].querySelector('a')?.getAttribute('href')).toBe('/cadastro?plano=BUSINESS');
  });

  it('explains availability and allows the visitor to expand frequently asked questions', () => {
    const fixture = TestBed.createComponent(Home);
    fixture.detectChanges();
    const page = fixture.nativeElement as HTMLElement;
    expect(page.querySelector('.availability')?.textContent).toContain('Disponível agora');
    expect(page.querySelector('.availability')?.textContent).toContain('Próximas etapas');
    const question = page.querySelector('details')!;
    expect(question.open).toBe(false);
    question.querySelector('summary')!.click();
    expect(question.open).toBe(true);
    expect(question.querySelector('p')?.textContent).toContain(
      'assistências técnicas independentes',
    );
  });
});
