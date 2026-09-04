import { Routes } from '@angular/router';
import { adminGuard } from './core/admin.guard';
import { authGuard } from './core/auth.guard';
import { AppShell } from './shell/app-shell';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./pages/login/login').then((m) => m.LoginPage),
  },
  {
    // Outside the shell and outside the guard, which is the one place in this
    // application that is true of. It has to be: everybody who needs this page
    // is somebody who cannot sign in, so putting it behind authGuard would make
    // it a door that only opens for people who do not need it. The invitation
    // page is the opposite case and stays inside — its holder can register.
    path: 'reset/:token',
    loadComponent: () => import('./pages/reset/reset').then((m) => m.ResetPage),
  },
  {
    // The other half of that journey, and outside the guard for the same reason:
    // asking for a link is something only somebody without a session ever does.
    path: 'forgot',
    loadComponent: () => import('./pages/forgot/forgot').then((m) => m.ForgotPage),
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
        // The itinerary as a document. Its own route rather than print styles on
        // the trip page: that page carries a map, a People panel and a day's
        // worth of controls, so printing it would be a long list of things to
        // hide — and it would still be missing the bookings, which live on
        // another page entirely and belong on the same sheet of paper.
        path: 'trips/:tripId/print',
        loadComponent: () => import('./pages/print/print').then((m) => m.PrintPage),
      },
      {
        // Inside the shell and behind the guard like everything else. A holder
        // with no account is bounced to /login with a returnUrl and comes back
        // here after registering, which is the entire journey an invite exists
        // for — and it costs no anonymous endpoint to support.
        path: 'invite/:token',
        loadComponent: () => import('./pages/invite/invite').then((m) => m.InvitePage),
      },
      {
        // Your own account, inside the shell like everything else. A page and
        // not a dialog: it is reached from the header on every screen, and a
        // password manager fills a real form far better than a modal.
        path: 'account',
        loadComponent: () => import('./pages/account/account').then((m) => m.AccountPage),
      },
      {
        // Administering the instance's accounts. adminGuard runs after
        // authGuard on this route and is a courtesy — the server refuses these
        // requests from a non-admin whatever the browser believes.
        path: 'admin',
        canActivate: [adminGuard],
        loadComponent: () => import('./pages/admin/admin').then((m) => m.AdminPage),
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
