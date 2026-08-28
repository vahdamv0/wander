import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  ApplicationRef,
  ComponentRef,
  createComponent,
  EnvironmentInjector,
  computed,
  effect,
  inject,
  input,
  output,
  viewChild,
} from '@angular/core';
import * as L from 'leaflet';
import { MapConfig, PlaceView, TripDay } from '../../api';
import { PlaceEnrichmentPanel } from './place-enrichment';

/** A place that has somewhere to be drawn, with the day it belongs to. */
interface Pin {
  place: PlaceView;
  dayIndex: number;
  latitude: number;
  longitude: number;
}

/** Zoom used when there is only one pin, so the map is not showing a continent. */
/** Breathing room between an open popup and the top of the map. */
const POPUP_MARGIN = 12;

/** How many frames to spend nudging a popup into view before giving up. */
const POPUP_REVEAL_ATTEMPTS = 3;

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
  /** A photo was kept or removed inside a popup; the page re-reads the itinerary. */
  readonly photoChanged = output<void>();

  private readonly canvas = viewChild.required<ElementRef<HTMLDivElement>>('canvas');

  private map: L.Map | null = null;
  private layer: L.LayerGroup | null = null;
  private readonly markers = new Map<number, L.Marker>();
  /**
   * The components currently rendered into popups.
   *
   * Leaflet builds popup DOM outside any template, so the alternative to this was
   * assembling HTML by hand in this file — no tokens, no bindings, no component.
   * Instead each popup gets a real `PlaceEnrichmentPanel` whose host element is
   * handed to Leaflet. They are tracked because an attached view that is never
   * destroyed leaks exactly like a map that is never removed, which this file
   * already has to remember for Leaflet's own listeners.
   */
  private readonly panels = new Map<number, ComponentRef<PlaceEnrichmentPanel>>();
  /**
   * One per open panel, because a popup's size is decided *after* Angular has
   * rendered into it — and again when the enrichment arrives a moment later.
   * Leaflet measures the content once, when `update()` is called, so without
   * this the layout it computes is of an empty box: no scroll container, no
   * `maxHeight`, and a popup taller than the map it lives in.
   */
  private readonly resizers = new Map<number, ResizeObserver>();
  /**
   * Which popups have had their auto-pan re-run since their content settled.
   *
   * Leaflet pans the map to fit a popup when it opens — while this one is still
   * empty, so it pans for nothing and the finished popup ends up over the map's
   * top edge. Reopening it once the content has arrived re-runs that pan with the
   * real size. Once per opening, or a resize and a reopen would chase each other.
   */
  private readonly panned = new Set<number>();
  private readonly appRef = inject(ApplicationRef);
  private readonly environmentInjector = inject(EnvironmentInjector);
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
      this.destroyPanels();
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

  /**
   * Renders a `PlaceEnrichmentPanel` into a popup that has just opened.
   *
   * `createComponent` plus `attachView` is what makes an Angular component work
   * inside DOM that Leaflet owns: the component is created against the
   * application's injector, attached so it takes part in change detection, and
   * its host element handed to Leaflet as the popup's content. Without the
   * attach it renders once and never updates — which looks like a bug in the
   * panel rather than in this file.
   */
  private fillPopup(marker: L.Marker, pin: Pin): void {
    const existing = this.panels.get(pin.place.id);
    if (existing) {
      // Reopened: the place may have gained a photo since, and inputs are how
      // that reaches the panel.
      existing.setInput('place', pin.place);
      return;
    }

    const panel = createComponent(PlaceEnrichmentPanel, {
      environmentInjector: this.environmentInjector,
    });
    panel.setInput('tripId', this.tripId());
    panel.setInput('place', pin.place);
    panel.setInput('dayIndex', pin.dayIndex);
    panel.setInput('canEdit', this.canEdit());
    panel.instance.photoChanged.subscribe(() => this.photoChanged.emit());

    this.appRef.attachView(panel.hostView);
    this.panels.set(pin.place.id, panel);

    const host = panel.location.nativeElement as HTMLElement;
    marker.getPopup()?.setContent(host);
    // Render before measuring. `attachView` schedules change detection rather
    // than running it, so without this the element handed to Leaflet is still
    // empty when it works out how big the popup should be.
    panel.changeDetectorRef.detectChanges();
    marker.getPopup()?.update();

    // And again whenever the panel changes height — the description and photos
    // arrive from the server after the popup is already open, which is the case
    // a one-off `update()` cannot cover.
    const resizer = new ResizeObserver(() => {
      marker.getPopup()?.update();
      this.revealPopup(marker);
    });
    resizer.observe(host);
    this.resizers.set(pin.place.id, resizer);
    marker.once('popupclose', () => this.panned.delete(pin.place.id));
  }

  /**
   * Pans the map so an open popup is fully visible.
   *
   * Leaflet does this itself when a popup opens — but this popup opens empty and
   * grows twice afterwards, so its own pan is computed against a box that is not
   * there yet and the finished popup ends up over the map's top edge. Reopening it
   * did not help, so the shortfall is measured and panned explicitly: it is a
   * subtraction, and it is right whatever the content turns out to be.
   *
   * Panning *down* means moving the view up, hence the negation.
   */
  private revealPopup(marker: L.Marker, attempt = 0): void {
    const map = this.map;
    const popup = marker.getPopup()?.getElement();
    if (!map || !popup || !marker.isPopupOpen()) {
      return;
    }
    const shortfall = map.getContainer().getBoundingClientRect().top + POPUP_MARGIN
      - popup.getBoundingClientRect().top;
    if (shortfall <= 1) {
      return;
    }
    map.panBy([0, -shortfall], { animate: false });

    // One more look on the next frame. The measurement above is taken while the
    // panel is still settling — a web font, an image, the last line of a wrapped
    // paragraph — so a single pass lands a few pixels short. Bounded, because a
    // popup taller than the map can never fit and must not be chased forever.
    if (attempt < POPUP_REVEAL_ATTEMPTS) {
      requestAnimationFrame(() => this.revealPopup(marker, attempt + 1));
    }
  }

  private destroyPanels(): void {
    for (const resizer of this.resizers.values()) {
      resizer.disconnect();
    }
    this.resizers.clear();
    this.panned.clear();
    for (const panel of this.panels.values()) {
      this.appRef.detachView(panel.hostView);
      panel.destroy();
    }
    this.panels.clear();
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
    // The markers these belonged to have just been thrown away.
    this.destroyPanels();

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
        // Bound empty and filled on open: building a panel for every pin up front
        // would fetch nothing (the panel does that) but would still create a
        // component per marker on every redraw, for popups nobody opens.
        //
        // No `maxWidth`/`maxHeight`: Leaflet computes those from the content at
        // the moment `update()` runs, and this content grows twice afterwards —
        // once when Angular renders and again when the enrichment arrives — so
        // they were being applied to an empty box and had no effect. The panel
        // sizes itself instead; `autoPanPadding` is the one option still worth
        // setting, so an opened popup is not flush against the map's edge.
        .bindPopup('', { autoPanPadding: [16, 16] })
        .on('click', () => this.placePicked.emit(pin.place));

      marker.on('popupopen', () => this.fillPopup(marker, pin));
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
    marker.openPopup();
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
