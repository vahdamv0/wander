import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withFetch, withXsrfConfiguration } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';
import { provideApiConfiguration } from './api/api-configuration';

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
  ],
};
