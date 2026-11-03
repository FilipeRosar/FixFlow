import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    title: 'FixFlow — Cada reparo, no seu fluxo.',
    loadComponent: () => import('./features/home/pages/home/home').then((module) => module.Home),
  },
  { path: '**', redirectTo: '' },
];
