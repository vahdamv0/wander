import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideHttpClient, withFetch, withXsrfConfiguration } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';
import { provideApiConfiguration } from './api/api-configuration';
import { SessionStore } from './core/session.store';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
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
  ],
};
