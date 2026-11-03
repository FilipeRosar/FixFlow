import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { App } from './app';
import { routes } from './app.routes';

describe('FixFlow navigation', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter(routes)],
    }).compileComponents();
  });

  it('renders the FixFlow home through the router', async () => {
    const fixture = TestBed.createComponent(App);
    await TestBed.inject(Router).navigateByUrl('/');
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    expect(page.querySelector('h1')?.textContent).toContain('Tudo no seu fluxo.');
    expect(page.querySelector('nav')?.getAttribute('aria-label')).toBe('Navegação principal');
    expect(page.querySelector('.demolabel')?.textContent).toContain('dados fictícios');
  });

  it('redirects an unknown route to the home', async () => {
    TestBed.createComponent(App);
    await TestBed.inject(Router).navigateByUrl('/pagina-inexistente');
    expect(TestBed.inject(Router).url).toBe('/');
  });
});
