import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  output,
  viewChild,
} from '@angular/core';
import * as L from 'leaflet';
import { MapConfig, PlaceView, TripDay } from '../../api';

/** A place that has somewhere to be drawn, with the day it belongs to. */
interface Pin {
  place: PlaceView;
  dayIndex: number;
  latitude: number;
  longitude: number;
}

/** Zoom used when there is only one pin, so the map is not showing a continent. */
const SINGLE_PIN_ZOOM = 15;

/**
 * The trip on a map.
 *
 * Leaflet owns its DOM subtree and knows nothing about signals, so this
 * component is the only place in the app that touches it: it creates the map
 * once after the first render, then reacts to signal changes by giving Leaflet
 * imperative instructions and tearing markers down itself. Nothing else in the
 * app imports leaflet.
 *
 * Tiles come from wherever the instance says — the URL and its attribution are
 * the operator's, not this component's, and the map is simply not drawn until
 * they have arrived.
 */
@Component({
  selector: 'app-trip-map',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="relative h-full w-full overflow-hidden rounded-control">
      <div #canvas class="h-full w-full"></div>
      @if (!pins().length) {
        <!-- pointer-events-none: the hint sits over a live map, and clicks
             through it should still pan. -->
        <div
          class="pointer-events-none absolute inset-0 grid place-items-center bg-surface/70 p-6
                 text-center text-sm text-muted"
        >
          <p class="max-w-xs">
            Places you add by searching show up here. Ones typed by hand have no
            location, so they have no pin.
          </p>
        </div>
      }
    </div>
  `,
})
export class TripMap {
  readonly days = input.required<TripDay[]>();
  readonly config = input.required<MapConfig>();
  /** The trip, so a popup can ask about its places. */
  readonly tripId = input.required<number>();
  /** Whether the viewer may keep a photo. Viewers get the facts and no controls. */
  readonly canEdit = input.required<boolean>();
  /** Set to pan to one place; cleared without moving the map. */
  readonly focus = input<PlaceView | null>(null);

  /** A marker click, so the page can highlight the matching row. */
  readonly placePicked = output<PlaceView>();

  private readonly canvas = viewChild.required<ElementRef<HTMLDivElement>>('canvas');

  private map: L.Map | null = null;
  private layer: L.LayerGroup | null = null;
  private readonly markers = new Map<number, L.Marker>();
  /**
   * Re-frame on every change until the viewer moves the map themselves. Framing
   * only once is wrong the moment a second place is added — the map stays zoomed
   * on the first pin — and re-framing forever would yank the view away from
   * someone who had panned somewhere deliberately.
   */
  private autoFrame = true;
  /**
   * True while *we* are moving the map. Leaflet does not distinguish a
   * programmatic move from a gesture — our own `setView` fires `zoomstart` just
   * as a pinch does — so without this the first framing would hand the view over
   * to a viewer who had not touched anything.
   */
  private framing = false;

  protected readonly pins = computed(() => toPins(this.days()));

  constructor() {
    const destroyRef = inject(DestroyRef);

    afterNextRender(() => {
      this.create();
      this.draw();
    });

    // Both of these fire before afterNextRender on the first pass, so each one
    // checks for a map rather than assuming one.
    effect(() => {
      this.days();
      this.draw();
    });

    effect(() => {
      const place = this.focus();
      if (place) {
        this.panTo(place);
      }
    });

    destroyRef.onDestroy(() => {
      // Leaflet keeps document-level listeners, so a map that is not removed
      // survives navigation and leaks.
      this.map?.remove();
      this.map = null;
    });
  }

  private create(): void {
    const config = this.config();
    this.map = L.map(this.canvas().nativeElement, {
      // The scroll wheel belongs to the page: a map that eats it makes a long
      // itinerary next to it impossible to scroll past.
      scrollWheelZoom: false,
      zoomControl: true,
      attributionControl: true,
    }).setView([20, 0], 2);

    L.tileLayer(config.tileUrl, {
      maxZoom: config.maxZoom,
      // Required by the tile service's terms, and Leaflet renders it into the
      // corner control for us.
      attribution: config.attribution,
    }).addTo(this.map);

    this.layer = L.layerGroup().addTo(this.map);

    // Any deliberate move hands the view over for good. `moveend` would fire for
    // our own fitBounds too, so these are the user-driven events only.
    this.map.on('dragstart zoomstart', () => {
      if (!this.framing) {
        this.autoFrame = false;
      }
    });
  }


  /** Rebuilds every marker. A trip has tens of places, not thousands. */
  private draw(): void {
    const map = this.map;
    const layer = this.layer;
    if (!map || !layer) {
      return;
    }
    layer.clearLayers();
    this.markers.clear();

    const pins = this.pins();
    for (const pin of pins) {
      const marker = L.marker([pin.latitude, pin.longitude], {
        icon: L.divIcon({
          className: 'map-pin-wrapper',
          html: `<span class="map-pin">${pin.dayIndex}</span>`,
          iconSize: [26, 26],
          iconAnchor: [13, 13],
        }),
        title: pin.place.name,
        keyboard: true,
        alt: `Day ${pin.dayIndex}: ${pin.place.name}`,
      })
        // A label, nothing more — and a tooltip rather than a popup, because a
        // popup on click could never be seen: the same click opens the detail
        // panel, which is a drawer over the whole map, and Leaflet auto-pans a
        // popup to fit the *map container*, knowing nothing about what covers
        // it. There was nowhere to pan to. The panel's own header carries these
        // same two lines, so the click has an answer and the label is for
        // pointing at a pin without committing to it.
        //
        // Leaflet opens a tooltip on focus as well as hover, so the keyboard
        // path keeps it; a touch tap has no hover and goes straight to the panel.
        .bindTooltip(mapLabel(pin), {
          direction: 'top',
          offset: [0, -14],
          opacity: 1,
        })
        .on('click', () => this.placePicked.emit(pin.place));

      marker.addTo(layer);
      this.markers.set(pin.place.id, marker);
    }

    if (this.autoFrame && pins.length) {
      this.frame(map, pins);
    }
  }

  private frame(map: L.Map, pins: Pin[]): void {
    // animate: false keeps the move synchronous, so the flag above covers every
    // event it raises rather than being cleared before an animation ends.
    this.framing = true;
    try {
      if (pins.length === 1) {
        map.setView([pins[0].latitude, pins[0].longitude], SINGLE_PIN_ZOOM, { animate: false });
        return;
      }
      map.fitBounds(L.latLngBounds(pins.map((pin) => L.latLng(pin.latitude, pin.longitude))), {
        padding: [32, 32],
        animate: false,
      });
    } finally {
      this.framing = false;
    }
  }

  private panTo(place: PlaceView): void {
    const map = this.map;
    const marker = this.markers.get(place.id);
    if (!map || !marker) {
      return;
    }
    // Ours as well — but this one stops the auto-framing on purpose: the viewer
    // asked to look at one place, and re-fitting on the next edit would undo it.
    this.framing = true;
    try {
      map.setView(marker.getLatLng(), Math.max(map.getZoom(), SINGLE_PIN_ZOOM), { animate: false });
    } finally {
      this.framing = false;
    }
    this.autoFrame = false;
    // Which of the pins is the one just asked for. It closes on the next hover
    // elsewhere, and nothing covers the map on this path — the pin button does
    // not open the panel.
    marker.openTooltip();
  }
}

function toPins(days: TripDay[]): Pin[] {
  const pins: Pin[] = [];
  for (const day of days) {
    for (const place of day.places) {
      // Both or neither, guaranteed by a CHECK on the table — tested here anyway
      // because the generated type has them optional.
      if (place.latitude != null && place.longitude != null) {
        pins.push({
          place,
          dayIndex: day.index,
          latitude: place.latitude,
          longitude: place.longitude,
        });
      }
    }
  }
  return pins;
}

/** Escaped: a place name is user input, and this string becomes HTML. */

/**
 * The label's whole content: which day, and the name. A marker click opens the
 * detail panel, so there is nothing else for a label to say.
 */
function mapLabel(pin: Pin): string {
  return `<p class="map-label-day">Day ${pin.dayIndex}</p>`
    + `<p class="map-label-name">${escapeHtml(pin.place.name)}</p>`;
}

/** Leaflet takes a string, so this is the one place in the client that escapes by hand. */
function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}
