import { TestBed } from '@angular/core/testing';
import { Home } from './home';

describe('Home interactions', () => {
  it('updates the journey description and pressed state when selecting a step', async () => {
    const fixture = TestBed.createComponent(Home);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    const steps = page.querySelectorAll<HTMLButtonElement>('.step');
    steps[1].click();
    await fixture.whenStable();
    expect(steps[1].getAttribute('aria-pressed')).toBe('true');
    expect(steps[0].getAttribute('aria-pressed')).toBe('false');
    expect(page.querySelector('#flowtext')?.textContent).toContain('Organize o diagnóstico');
  });

  it('opens and closes the product presentation using the native dialog', async () => {
    const fixture = TestBed.createComponent(Home);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    const dialog = page.querySelector('dialog')!;
    dialog.showModal = vi.fn();
    dialog.close = vi.fn();
    page.querySelector<HTMLButtonElement>('.cta button')!.click();
    expect(dialog.showModal).toHaveBeenCalledOnce();
    page.querySelector<HTMLButtonElement>('dialog .close')!.click();
    expect(dialog.close).toHaveBeenCalledOnce();
  });

  it('keeps the dialog open when clicking its padding and closes on the backdrop', async () => {
    const fixture = TestBed.createComponent(Home);
    await fixture.whenStable();
    const dialog = (fixture.nativeElement as HTMLElement).querySelector('dialog')!;
    dialog.close = vi.fn();
    vi.spyOn(dialog, 'getBoundingClientRect').mockReturnValue({
      left: 100,
      right: 500,
      top: 100,
      bottom: 500,
    } as DOMRect);
    dialog.dispatchEvent(new MouseEvent('click', { clientX: 120, clientY: 120 }));
    expect(dialog.close).not.toHaveBeenCalled();
    dialog.dispatchEvent(new MouseEvent('click', { clientX: 10, clientY: 10 }));
    expect(dialog.close).toHaveBeenCalledOnce();
  });
});
