import { Routes } from '@angular/router';
import { sessionGuard } from './core/auth/guards/session.guard';
export const routes: Routes = [
  {
    path: '',
    title: 'FixFlow — Gestão para assistências técnicas',
    loadComponent: () => import('./features/home/pages/home/home').then((m) => m.Home),
  },
  {
    path: 'entrar',
    title: 'Entrar — FixFlow',
    loadComponent: () => import('./features/auth/pages/login/login').then((m) => m.Login),
  },
  {
    path: 'cadastro',
    title: 'Criar conta — FixFlow',
    loadComponent: () => import('./features/auth/pages/register/register').then((m) => m.Register),
  },
  {
    path: 'recuperar-senha',
    title: 'Recuperar senha — FixFlow',
    loadComponent: () => import('./features/auth/pages/recovery/recovery').then((m) => m.Recovery),
  },
  {
    path: 'redefinir-senha',
    title: 'Redefinir senha — FixFlow',
    data: { reset: true },
    loadComponent: () => import('./features/auth/pages/recovery/recovery').then((m) => m.Recovery),
  },
  {
    path: 'inicio/equipamentos',
    title: 'Equipamentos — FixFlow',
    data: { page: 'equipment' },
    canActivate: [sessionGuard],
    loadComponent: () =>
      import('./features/workspace/pages/workspace/workspace').then((m) => m.Workspace),
  },
  {
    path: 'inicio/clientes',
    title: 'Clientes — FixFlow',
    data: { page: 'customers' },
    canActivate: [sessionGuard],
    loadComponent: () =>
      import('./features/workspace/pages/workspace/workspace').then((m) => m.Workspace),
  },
  {
    path: 'inicio/configuracoes',
    title: 'Configurações da empresa — FixFlow',
    data: { page: 'settings', requiredRole: 'OWNER' },
    canActivate: [sessionGuard],
    loadComponent: () =>
      import('./features/workspace/pages/workspace/workspace').then((m) => m.Workspace),
  },
  {
    path: 'inicio',
    title: 'Minha empresa — FixFlow',
    canActivate: [sessionGuard],
    loadComponent: () =>
      import('./features/workspace/pages/workspace/workspace').then((m) => m.Workspace),
  },
  { path: '**', redirectTo: '' },
];
