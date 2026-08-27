package com.wander.place;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wander.place.dto.CreatePlaceRequest;
import com.wander.place.dto.MovePlaceRequest;
import com.wander.place.dto.PlaceView;
import com.wander.place.dto.TripItinerary;
import com.wander.place.dto.UpdatePlaceRequest;
import com.wander.security.WanderUser;

import jakarta.validation.Valid;

/**
 * Method names are the OpenAPI operation ids, and ng-openapi-gen exports them
 * unqualified into one barrel file — so they are `createPlace`, not `create`,
 * which would collide with TripController's.
 */
@RestController
@RequestMapping("/api/trips/{tripId}")
public class PlaceController {

    private final PlaceService places;

    public PlaceController(PlaceService places) {
        this.places = places;
    }

    /** The whole page in one call: the trip, every derived day, and its places. */
    @GetMapping("/itinerary")
    public TripItinerary getItinerary(@AuthenticationPrincipal WanderUser principal,
            @PathVariable Long tripId) {
        return places.itinerary(principal.id(), tripId);
    }

    @PostMapping("/places")
    @ResponseStatus(HttpStatus.CREATED)
    public PlaceView createPlace(@AuthenticationPrincipal WanderUser principal,
            @PathVariable Long tripId, @Valid @RequestBody CreatePlaceRequest request) {
        return places.create(principal.id(), tripId, request);
    }

    @PutMapping("/places/{placeId}")
    public PlaceView updatePlace(@AuthenticationPrincipal WanderUser principal,
            @PathVariable Long tripId, @PathVariable Long placeId,
            @Valid @RequestBody UpdatePlaceRequest request) {
        return places.update(principal.id(), tripId, placeId, request);
    }

    /** Reordering within a day and moving between days are the same operation. */
    @PostMapping("/places/{placeId}/move")
    public PlaceView movePlace(@AuthenticationPrincipal WanderUser principal,
            @PathVariable Long tripId, @PathVariable Long placeId,
            @Valid @RequestBody MovePlaceRequest request) {
        return places.move(principal.id(), tripId, placeId, request);
    }

    @DeleteMapping("/places/{placeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePlace(@AuthenticationPrincipal WanderUser principal,
            @PathVariable Long tripId, @PathVariable Long placeId) {
        places.delete(principal.id(), tripId, placeId);
    }
}
