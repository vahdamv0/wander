import { Routes } from '@angular/router';
import { authGuard } from './core/auth.guard';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'trips' },
  {
    path: 'login',
    loadComponent: () => import('./pages/login/login').then((m) => m.LoginPage),
  },
  {
    path: 'trips',
    canActivate: [authGuard],
    loadComponent: () => import('./pages/trips/trips').then((m) => m.TripsPage),
  },
  { path: '**', redirectTo: 'trips' },
];
