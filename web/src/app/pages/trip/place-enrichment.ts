import { Component, computed, inject, input, output, signal } from '@angular/core';
import { PlaceView } from '../../api';
import { messageOf } from '../../core/errors';
import { EnrichmentRepo } from '../../repo/enrichment.repo';

/**
 * The contents of a map popup: what the world says about this place.
 *
 * A real component rather than HTML assembled in `trip-map.ts`, even though
 * Leaflet builds its popups outside any Angular template. The map file creates
 * one of these with `createComponent` and hands Leaflet its host element, which
 * keeps the markup here — with the design tokens, signals and a normal template —
 * and keeps `trip-map.ts` owning nothing but the map.
 *
 * Everything shown carries where it came from. That is not decoration: the
 * description is CC BY-SA and must be credited, each photograph is licensed
 * individually, and the opening hours are a stranger's note of unknown age.
 */
@Component({
  selector: 'app-place-enrichment',
  // A component element is inline by default, which gives it no box of its own —
  // and this element is handed to Leaflet and watched by a ResizeObserver, both of
  // which need one. Without this the observer never reports a size and the popup
  // is never re-measured or panned into view.
  styles: [':host { display: block; }'],
  template: `
    <!-- The panel sizes itself rather than relying on Leaflet's popup options.
         Leaflet measures its content once, when update() is called, and the
         content here grows twice afterwards - when Angular renders, and again
         when the enrichment arrives - so its maxWidth and maxHeight were computed
         against an empty box and never applied. Owning the box is simpler and the
         only version that stays correct as the content changes.
         (No backticks in here: this is inside a TypeScript template literal.) -->
    <div class="w-[15rem] max-h-[11rem] overflow-y-auto pr-1 sm:w-[16rem] sm:max-h-[14rem]">
    <p class="map-popup-day">Day {{ dayIndex() }}</p>
    <p class="map-popup-name">{{ place().name }}</p>
    @if (place().address) {
      <p class="map-popup-detail">{{ place().address }}</p>
    }

    @if (facts(); as facts) {
      @if (facts.available) {
        @if (facts.summary) {
          <p class="mt-2 text-xs leading-relaxed">{{ facts.summary }}</p>
          <p class="mt-1 text-[0.6875rem] text-muted">
            @if (facts.summaryUrl) {
              <a [href]="facts.summaryUrl" target="_blank" rel="noopener noreferrer"
                 class="underline underline-offset-2">Wikipedia</a>
            } @else {
              Wikipedia
            }
            @if (facts.summaryLicence) {
              · {{ facts.summaryLicence }}
            }
          </p>
        }

        @if (facts.openingHours) {
          <div class="mt-2 rounded-control bg-surface-2 px-2 py-1.5">
            <!-- Shown as OpenStreetMap wrote it, with the date it was read. An
                 app-computed "open now" would be a claim this data cannot
                 support, and people plan their day around hours. -->
            <p class="text-[0.6875rem] font-medium uppercase tracking-wide text-muted">Hours</p>
            <p class="whitespace-pre-line text-xs">{{ facts.openingHours }}</p>
            <p class="text-[0.6875rem] text-muted">
              OpenStreetMap
              @if (facts.fetchedAt) {
                · read {{ readOn(facts.fetchedAt) }}
              }
              · may be out of date
            </p>
          </div>
        }

        @if (facts.website || facts.phone) {
          <p class="mt-2 flex flex-wrap gap-x-3 text-xs">
            @if (facts.website) {
              <a [href]="facts.website" target="_blank" rel="noopener noreferrer"
                 class="underline underline-offset-2">Website</a>
            }
            @if (facts.phone) {
              <a [href]="'tel:' + facts.phone" class="underline underline-offset-2">
                {{ facts.phone }}
              </a>
            }
          </p>
        }

        @if (facts.photos.length && canEdit()) {
          <div class="mt-2">
            <p class="text-[0.6875rem] font-medium uppercase tracking-wide text-muted">Photos</p>
            <ul class="mt-1 flex flex-wrap gap-1.5">
              @for (photo of facts.photos; track photo.url) {
                <li>
                  <button type="button"
                          class="block overflow-hidden rounded-control border border-border"
                          [class.border-accent]="place().photoUrl === photo.url"
                          [disabled]="saving()"
                          [title]="photo.author + ' · ' + photo.licence"
                          (click)="keep(photo)">
                    <img [src]="photo.thumbUrl" [alt]="'Photo of ' + place().name"
                         class="h-12 w-16 object-cover" />
                  </button>
                </li>
              }
            </ul>
            @if (chosen(); as chosen) {
              <!-- The credit is not optional and not a tooltip: a Commons image is
                   licensed per image, and this is where the obligation is met. -->
              <p class="mt-1 text-[0.6875rem] text-muted">
                {{ chosen.author }} · {{ chosen.licence }}
                @if (chosen.sourceUrl) {
                  ·
                  <a [href]="chosen.sourceUrl" target="_blank" rel="noopener noreferrer"
                     class="underline underline-offset-2">Commons</a>
                }
                · <button type="button" class="underline underline-offset-2"
                          [disabled]="saving()" (click)="keep(null)">remove</button>
              </p>
            } @else {
              <p class="mt-1 text-[0.6875rem] text-muted">Pick one to keep it on this place.</p>
            }
          </div>
        }
      }
    } @else {
      <p class="mt-2 text-xs text-muted">Looking…</p>
    }

    @if (error(); as error) {
      <p class="mt-2 text-xs text-danger" role="alert">{{ error }}</p>
    }
    </div>
  `,
})
export class PlaceEnrichmentPanel {
  private readonly repo = inject(EnrichmentRepo);

  readonly tripId = input.required<number>();
  readonly place = input.required<PlaceView>();
  readonly dayIndex = input.required<number>();
  readonly canEdit = input.required<boolean>();

  /** Raised after a photo is kept or removed, so the page re-reads the itinerary. */
  readonly photoChanged = output<void>();

  protected readonly saving = this.repo.saving;
  protected readonly error = signal<string | null>(null);

  protected readonly facts = computed(() => this.repo.forPlace()[this.place().id]);

  /** The credit for the photo currently kept, taken from the candidate list. */
  protected readonly chosen = computed(() => {
    const url = this.place().photoUrl;
    return url ? this.facts()?.photos.find((photo) => photo.url === url) : undefined;
  });

  constructor() {
    // The popup is opened by a click, so fetching here is fetching on demand:
    // nothing is asked about a place nobody looked at.
    queueMicrotask(() => void this.load());
  }

  private async load(): Promise<void> {
    try {
      await this.repo.load(this.tripId(), this.place().id);
    } catch (err: unknown) {
      this.error.set(messageOf(err, 'Could not look this place up.'));
    }
  }

  protected readOn(fetchedAt: string): string {
    return new Date(fetchedAt).toLocaleDateString(undefined, { month: 'short', year: 'numeric' });
  }

  protected async keep(
    photo: { url: string; thumbUrl: string; author: string; licence: string; sourceUrl: string }
      | null,
  ): Promise<void> {
    this.error.set(null);
    try {
      await this.repo.setPhoto(this.tripId(), this.place().id, photo);
      this.photoChanged.emit();
    } catch (err: unknown) {
      this.error.set(messageOf(err, 'Could not keep that photo.'));
    }
  }
}
