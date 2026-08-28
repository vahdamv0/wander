import {
  ApplicationConfig,
  inject,
  isDevMode,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideServiceWorker } from '@angular/service-worker';
import { provideHttpClient, withFetch, withXsrfConfiguration } from '@angular/common/http';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { routes } from './app.routes';
import { provideApiConfiguration } from './api/api-configuration';
import { SessionStore } from './core/session.store';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    // withComponentInputBinding: a route param arrives as a component input()
    // rather than through ActivatedRoute, which keeps pages free of router types.
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(
      withFetch(),
      // Matches CookieCsrfTokenRepository on the server. Angular only attaches
      // the header to relative URLs, which is the other reason rootUrl below is
      // empty rather than an absolute origin.
      withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' }),
    ),
    // Same-origin: the API is served by the same jar as this app. Without this
    // the generated client would use whatever host produced the OpenAPI doc.
    provideApiConfiguration(''),
    // Resolve the session before the first route renders. Two reasons, and the
    // second one is easy to miss:
    //   1. the app knows immediately whether it is signed in, so a reload does
    //      not flash the login page at someone who already is;
    //   2. it guarantees one GET to the API before any POST, which is what
    //      hands the browser its XSRF-TOKEN cookie. Under `ng serve` the shell
    //      comes from Vite rather than Spring, so without this the very first
    //      login attempt has no CSRF token and is rejected with a 403.
    provideAppInitializer(() => inject(SessionStore).restore()),
    // Without this the app is simply unreachable with no signal: the browser
    // cannot fetch index.html and shows its own offline page, cache or no cache.
    //
    // App shell and assets only — there are deliberately no `dataGroups` in
    // ngsw-config.json. The repos cache API responses in IndexedDB, where the
    // page can say how old a copy is; a second cache inside the worker would be
    // an invisible one with its own staleness rules that nothing on screen could
    // explain.
    provideServiceWorker('ngsw-worker.js', {
      enabled: !isDevMode(),
      // Registered once the app settles rather than during startup, so the
      // worker never competes with the first render or the session restore. The
      // first visit is therefore not offline-capable; the next one is. A new
      // build is picked up on the following load rather than reloading the page
      // under somebody mid-edit.
      registrationStrategy: 'registerWhenStable:30000',
    }),
  ],
};
