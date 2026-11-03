import { Component, signal } from '@angular/core';
import { JOURNEY_STEPS } from '../../data/journey-steps';
import { AboutDialog } from '../../components/about-dialog/about-dialog';

@Component({
  selector: 'app-home',
  imports: [AboutDialog],
  templateUrl: './home.html',
})
export class Home {
  protected readonly steps = JOURNEY_STEPS;
  protected readonly selectedStep = signal(0);

  protected selectStep(index: number): void {
    this.selectedStep.set(index);
  }
}
