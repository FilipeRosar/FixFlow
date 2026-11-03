import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { App } from './app';
import { routes } from './app.routes';

describe('FixFlow navigation', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
  });

  it('renders the FixFlow home through the router', async () => {
    const fixture = TestBed.createComponent(App);
    await TestBed.inject(Router).navigateByUrl('/');
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    expect(page.querySelector('h1')?.textContent).toContain('Seu talento é reparar.');
    expect(page.querySelector('nav')?.getAttribute('aria-label')).toBe('Navegação principal');
    expect(page.querySelector('.release-note')?.textContent).toContain('Em desenvolvimento');
  });

  it('redirects an unknown route to the home', async () => {
    TestBed.createComponent(App);
    await TestBed.inject(Router).navigateByUrl('/pagina-inexistente');
    expect(TestBed.inject(Router).url).toBe('/');
  });
});
