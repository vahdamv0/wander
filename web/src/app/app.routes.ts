import { Routes } from '@angular/router';
import { authGuard } from './core/auth.guard';
import { AppShell } from './shell/app-shell';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./pages/login/login').then((m) => m.LoginPage),
  },
  {
    // Everything signed-in renders inside the shell, so a page never draws the
    // header itself.
    path: '',
    component: AppShell,
    canActivate: [authGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'trips' },
      {
        path: 'trips',
        loadComponent: () => import('./pages/trips/trips').then((m) => m.TripsPage),
      },
      { path: '**', redirectTo: 'trips' },
    ],
  },
];
