import { Routes } from "@angular/router";
import { sessionGuard } from "./core/auth/guards/session.guard";
export const routes: Routes = [
  {
    path: "",
    title: "FixFlow — Gestão para assistências técnicas",
    loadComponent: () =>
      import("./features/home/pages/home/home").then((m) => m.Home),
  },
  {
    path: "entrar",
    title: "Entrar — FixFlow",
    loadComponent: () =>
      import("./features/auth/pages/login/login").then((m) => m.Login),
  },
  {
    path: "cadastro",
    title: "Criar conta — FixFlow",
    loadComponent: () =>
      import("./features/auth/pages/register/register").then((m) => m.Register),
  },
  {
    path: "inicio",
    title: "Minha empresa — FixFlow",
    canActivate: [sessionGuard],
    loadComponent: () =>
      import("./features/workspace/pages/workspace/workspace").then(
        (m) => m.Workspace,
      ),
  },
  { path: "**", redirectTo: "" },
];
