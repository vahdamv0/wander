import { Component } from '@angular/core';

/**
 * wander's mark: a path bending between two points. Drawn inline rather than
 * loaded as an asset so it inherits currentColor and needs no request.
 */
@Component({
  selector: 'app-brand-mark',
  template: `
    <svg viewBox="0 0 28 28" fill="none" aria-hidden="true" class="size-7">
      <circle cx="14" cy="14" r="13" class="stroke-accent/25" stroke-width="1.5" />
      <path
        d="M7 20c1.8-5.4 4-8.6 6.6-9.6 2.6-1 5 .3 7.4 4"
        class="stroke-accent"
        stroke-width="2"
        stroke-linecap="round"
        stroke-dasharray="1 3.6"
      />
      <circle cx="7" cy="20" r="2.4" class="fill-accent" />
      <path d="M21 8.4 22.6 12l-3.7-.5Z" class="fill-accent" />
    </svg>
  `,
})
export class BrandMark {}
