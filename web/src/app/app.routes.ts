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
      {
        // `tripId` arrives as a component input — withComponentInputBinding is
        // on in app.config.ts, so the page needs no ActivatedRoute.
        path: 'trips/:tripId',
        loadComponent: () => import('./pages/trip/trip').then((m) => m.TripPage),
      },
      {
        // Its own page rather than a panel: the trip page already carries a map,
        // a day list and a People panel, and money deserves room.
        path: 'trips/:tripId/expenses',
        loadComponent: () => import('./pages/expenses/expenses').then((m) => m.ExpensesPage),
      },
      {
        path: 'trips/:tripId/packing',
        loadComponent: () => import('./pages/packing/packing').then((m) => m.PackingPage),
      },
      {
        path: 'trips/:tripId/reservations',
        loadComponent: () =>
          import('./pages/reservations/reservations').then((m) => m.ReservationsPage),
      },
      { path: '**', redirectTo: 'trips' },
    ],
  },
];
