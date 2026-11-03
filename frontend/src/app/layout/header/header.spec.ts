import { TestBed } from '@angular/core/testing';
import { Header } from './header';

describe('Mobile navigation', () => {
  it('opens the menu and closes it after choosing a section', async () => {
    const fixture = TestBed.createComponent(Header);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    const toggle = page.querySelector<HTMLButtonElement>('.menu-toggle')!;
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    toggle.click();
    await fixture.whenStable();
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(page.querySelector('.links')?.classList.contains('is-open')).toBe(true);
    page.querySelector<HTMLAnchorElement>('.links a')!.click();
    await fixture.whenStable();
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
  });
});
