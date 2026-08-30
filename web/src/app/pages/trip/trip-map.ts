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
// Side-effect import: the bridge registers L.maplibreGL and pulls in maplibre-gl
// itself, so the map is still constructed through Leaflet and not from here.
import '@maplibre/maplibre-gl-leaflet';
import * as L from 'leaflet';
import {
  type ExpressionSpecification,
  type Map as MaplibreMap,
  setWorkerUrl,
} from 'maplibre-gl';
import { MapConfig, PlaceView, TripDay } from '../../api';
import { ThemeService } from '../../core/theme.service';

/** A place that has somewhere to be drawn, with the day it belongs to. */
interface Pin {
  place: PlaceView;
  dayIndex: number;
  latitude: number;
  longitude: number;
}

/**
 * MapLibre parses vector tiles in a web worker, which it loads **by URL at
 * runtime** — so no bundler ever sees it as an import and none of them emit it.
 * `angular.json` copies it — and the shared chunk it imports by a relative path,
 * so the two have to stay side by side — out of the package, and this points at
 * the copy.
 *
 * Worth knowing how this fails, because nothing at all points at it: the default
 * URL is derived from the chunk's own `import.meta.url`, so the request went to
 * `/maplibre-gl-worker.mjs`, which the SPA fallback answered with `index.html`.
 * The worker then died on the first line with **nothing** logged — no console
 * error, no MapLibre `error` event — and the only symptom was a basemap that
 * stayed blank while the style document, the sprites and the markers all loaded
 * perfectly. Deriving it from `document.baseURI` instead makes it independent of
 * where the chunk lands.
 */
setWorkerUrl(new URL('maplibre-gl-worker.mjs', document.baseURI).href);

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
 *
 * **The basemap is vector, drawn by MapLibre inside Leaflet.** Leaflet still owns
 * the map, the markers, the tooltips and the framing; only the tile layer is
 * different. That is the whole reason for the bridge — a raster tile is a picture
 * with the local name painted into it, so a trip to Japan was labelled 東京都
 * however the browser asked, while vector tiles carry `name`, `name:latin` and
 * `name:xx` as data and let the client choose. wander asks for both: the
 * reader's language over the local name, so a place matches both the plan and
 * the signs in front of you.
 *
 * A raster `tileUrl` is still supported for an instance that has its own tile
 * server; it simply cannot do the language part.
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

  private readonly theme = inject(ThemeService);

  private map: L.Map | null = null;
  private layer: L.LayerGroup | null = null;
  /** The vector basemap, when there is one. Null on the raster path. */
  private basemap: L.MaplibreGL | null = null;
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
  /** Which style is loaded, so a theme change does not reload the same one. */
  private currentStyleUrl = '';

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

    // The basemap style follows the theme — a genuine improvement on the raster
    // path, which can only put a CSS brightness filter over tiles drawn for a
    // light background.
    //
    // Declared here rather than beside the layer it drives: `addBasemap` runs
    // from `afterNextRender`, which is not an injection context, and calling
    // `effect()` there throws NG0203 — taking the rest of map creation with it,
    // so the symptom was a map with no markers at all.
    effect(() => {
      const dark = this.theme.isDark();
      const config = this.config();
      const gl = this.basemap?.getMaplibreMap();
      if (!gl || !config.styleUrl) {
        return;
      }
      const wanted = dark && config.darkStyleUrl ? config.darkStyleUrl : config.styleUrl;
      if (wanted !== this.currentStyleUrl) {
        this.currentStyleUrl = wanted;
        gl.setStyle(wanted);
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

    this.addBasemap(config);

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
   * The basemap: a MapLibre vector style if the instance has one, otherwise the
   * raster tile layer.
   *
   * Both are ordinary Leaflet layers, which is the point — everything after this
   * line (markers, tooltips, framing, teardown) is unchanged either way.
   */
  private addBasemap(config: MapConfig): void {
    const map = this.map;
    if (!map) {
      return;
    }

    if (!config.styleUrl) {
      L.tileLayer(config.tileUrl, {
        maxZoom: config.maxZoom,
        // Required by the tile service's terms, and Leaflet renders it into the
        // corner control for us.
        attribution: config.attribution,
      }).addTo(map);
      return;
    }

    this.basemap = L.maplibreGL({
      style: this.styleUrlFor(config),
      // Leaflet's corner control is the one on screen; MapLibre's own would sit
      // inside the canvas and say the same thing twice.
      attributionControl: false,
      // CJK, Korean and Japanese rendered from the reader's own installed fonts
      // instead of downloading glyph ranges for tens of thousands of characters.
      // Without it a map of Japan pulls megabytes of font data before a single
      // label appears.
      localIdeographFontFamily: "'Noto Sans CJK JP', 'Hiragino Sans', 'Yu Gothic', sans-serif",
    }).addTo(map);
    // Required by every tile service's terms. Added to Leaflet's control rather
    // than passed as a layer option, which the bridge's typings do not carry.
    map.attributionControl.addAttribution(config.attribution);

    const gl = this.basemap.getMaplibreMap();
    // 'style.load' rather than 'styledata': the latter also fires for our own
    // setLayoutProperty calls below, so applying the language from it would
    // re-enter itself on every label layer.
    gl.on('style.load', () => this.applyLabelLanguage(gl));
  }

  private styleUrlFor(config: MapConfig): string {
    const url = this.theme.isDark() && config.darkStyleUrl ? config.darkStyleUrl : config.styleUrl;
    this.currentStyleUrl = url;
    return url;
  }

  /**
   * Labels in the reader's language, over the local name.
   *
   * OpenMapTiles carries `name` (local), `name:latin`, `name:nonlatin` and a
   * `name:xx` per language, so this is a layout-property rewrite rather than a
   * different tile source. Both lines are kept deliberately: on a trip the
   * useful map is the one you can read *and* match against the signs in front of
   * you.
   *
   * Only layers whose label already mentions a name are touched. A house number
   * layer's `text-field` is `{housenumber}`, and rewriting that to a name
   * expression would blank every number at street zoom — which nothing else here
   * would have noticed.
   */
  private applyLabelLanguage(gl: MaplibreMap): void {
    const language = navigator.language?.split('-')[0];
    if (!language) {
      return;
    }
    // The reader's language, falling back to the Latin transliteration and then
    // to whatever the place calls itself — so there is always something to draw.
    const preferred: ExpressionSpecification = [
      'coalesce',
      ['get', `name:${language}`],
      ['get', 'name:latin'],
      ['get', 'name'],
    ];

    for (const layer of gl.getStyle().layers) {
      const field = (layer as { layout?: Record<string, unknown> }).layout?.['text-field'];
      if (field === undefined || !JSON.stringify(field).includes('name')) {
        continue;
      }
      const bilingual: ExpressionSpecification = [
        'case',
        ['all', ['has', 'name:nonlatin'], ['!=', ['get', 'name:nonlatin'], '']],
        ['concat', preferred, '\n', ['get', 'name:nonlatin']],
        preferred,
      ];
      gl.setLayoutProperty(layer.id, 'text-field', bilingual);
    }
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
