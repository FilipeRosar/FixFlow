import { Component, inject } from '@angular/core';
import { NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { filter, map } from 'rxjs';
import { Header } from './layout/header/header';
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, Header],
  templateUrl: './app.html',
})
export class App {
  private readonly router = inject(Router);
  protected readonly operational = toSignal(
    this.router.events.pipe(
      filter((event) => event instanceof NavigationEnd),
      map(() => this.isOperational()),
    ),
    { initialValue: this.isOperational() },
  );
  private isOperational(): boolean {
    return /^\/inicio(?:[/?#]|$)/.test(this.router.url);
  }
}
