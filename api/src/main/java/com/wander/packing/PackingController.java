package com.wander.packing;

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

import com.wander.packing.dto.PackedRequest;
import com.wander.packing.dto.PackingItemRequest;
import com.wander.packing.dto.PackingItemView;
import com.wander.packing.dto.TripPacking;
import com.wander.security.WanderUser;

import jakarta.validation.Valid;

/**
 * The trip's packing list. Operation ids are global on the generated client, so
 * they are named for the resource rather than the verb alone.
 */
@RestController
@RequestMapping("/api/trips/{tripId}/packing")
public class PackingController {

    private final PackingService packing;

    public PackingController(PackingService packing) {
        this.packing = packing;
    }

    @GetMapping
    public TripPacking listPacking(@AuthenticationPrincipal WanderUser principal,
            @PathVariable Long tripId) {
        return packing.list(principal.id(), tripId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PackingItemView createPackingItem(@AuthenticationPrincipal WanderUser principal,
            @PathVariable Long tripId,
            @Valid @RequestBody PackingItemRequest request) {
        return packing.create(principal.id(), tripId, request);
    }

    @PutMapping("/{itemId}")
    public PackingItemView updatePackingItem(@AuthenticationPrincipal WanderUser principal,
            @PathVariable Long tripId,
            @PathVariable Long itemId,
            @Valid @RequestBody PackingItemRequest request) {
        return packing.update(principal.id(), tripId, itemId, request);
    }

    /**
     * Ticking, on its own so that flipping a checkbox cannot carry a stale
     * description along with it and undo somebody else's rename.
     */
    @PutMapping("/{itemId}/packed")
    public PackingItemView setPackingItemPacked(@AuthenticationPrincipal WanderUser principal,
            @PathVariable Long tripId,
            @PathVariable Long itemId,
            @Valid @RequestBody PackedRequest request) {
        return packing.setPacked(principal.id(), tripId, itemId, request);
    }

    @DeleteMapping("/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePackingItem(@AuthenticationPrincipal WanderUser principal,
            @PathVariable Long tripId,
            @PathVariable Long itemId) {
        packing.delete(principal.id(), tripId, itemId);
    }
}
