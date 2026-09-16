import { Directive, signal } from '@angular/core';

/**
 * Whether a photograph actually arrived.
 *
 * A kept photo is a URL on Wikimedia Commons, and a URL on somebody else's
 * service can stop working without anything here changing: a file is renamed or
 * deleted upstream, a reader is behind a filter that does not like the host, or
 * — the case that prompted this — a service worker installed under an older
 * Content-Security-Policy refuses its own fetch and answers a synthetic 504.
 * The browser's own answer to all of them is the broken-image glyph with the
 * `alt` text spilling out beside it, which in a 36px row is unreadable, wider
 * than the picture it replaces, and says nothing a reader can act on.
 *
 * It reports rather than hides, and that is the point: the three places that
 * draw a photo want different things. A row drops the image and closes the gap,
 * the printed page drops the whole figure because a broken picture is just ink,
 * and the detail panel keeps its figure — the credit and the **remove** control
 * live in that caption, so hiding it would take away the one way to be rid of a
 * photo that no longer loads.
 *
 * `load` resets it because the element outlives the `src`: the detail panel is
 * one component that shows whichever place is selected, so a failure on one
 * place must not blank the next one's picture.
 */
@Directive({
  selector: 'img[wanderPhoto]',
  exportAs: 'wanderPhoto',
  host: {
    '(error)': 'failed.set(true)',
    '(load)': 'failed.set(false)',
  },
})
export class PhotoLoad {
  readonly failed = signal(false);
}
