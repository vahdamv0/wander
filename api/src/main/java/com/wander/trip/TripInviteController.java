package com.wander.trip;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

import com.wander.security.WanderUser;
import com.wander.trip.dto.AcceptedInviteView;
import com.wander.trip.dto.CreateInviteRequest;
import com.wander.trip.dto.CreatedInviteView;
import com.wander.trip.dto.InvitePreview;
import com.wander.trip.dto.TripInviteView;

import jakarta.validation.Valid;

/**
 * Invitation links.
 *
 * Two roots on one controller, because they are two halves of one feature with
 * opposite audiences: `/api/trips/{tripId}/invites` is the owner minting and
 * managing links, and `/api/invites/{token}` is the recipient looking at one.
 * The second is **not** keyed by trip id — the whole point is that its holder
 * cannot see the trip yet.
 *
 * Every endpoint here is authenticated, including the recipient's. Somebody with
 * no account signs up first and then accepts, which costs one extra step and
 * keeps this feature off the anonymous surface entirely — see `@PublicEndpoint`
 * and `EndpointAuthRatchetTest`.
 *
 * The password reset link, which arrived later, is the case where that argument
 * does not hold: an invitation's holder can always register first, whereas
 * everybody who needs a reset is somebody who cannot sign in. So that one is
 * anonymous and this one is not, and the difference is the point rather than an
 * inconsistency.
 *
 * Method names are the operation ids and ng-openapi-gen exports them unqualified,
 * hence `createInvite` and `listInvites` rather than `create` and `list`.
 */
@RestController
public class TripInviteController {

    private final TripInviteService invites;

    public TripInviteController(TripInviteService invites) {
        this.invites = invites;
    }

    @PostMapping("/api/trips/{tripId}/invites")
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedInviteView createInvite(@AuthenticationPrincipal WanderUser principal,
            @PathVariable Long tripId,
            @Valid @RequestBody CreateInviteRequest request) {
        return invites.create(principal.id(), tripId, request);
    }

    @GetMapping("/api/trips/{tripId}/invites")
    public List<TripInviteView> listInvites(@AuthenticationPrincipal WanderUser principal,
            @PathVariable Long tripId) {
        return invites.list(principal.id(), tripId);
    }

    @DeleteMapping("/api/trips/{tripId}/invites/{inviteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeInvite(@AuthenticationPrincipal WanderUser principal,
            @PathVariable Long tripId,
            @PathVariable Long inviteId) {
        invites.revoke(principal.id(), tripId, inviteId);
    }

    /** What the link says before you commit to it. */
    @GetMapping("/api/invites/{token}")
    public InvitePreview previewInvite(@AuthenticationPrincipal WanderUser principal,
            @PathVariable String token) {
        return invites.preview(principal.id(), token);
    }

    /** Joins the trip and returns its id, so the client can go straight there. */
    @PostMapping("/api/invites/{token}/accept")
    public AcceptedInviteView acceptInvite(@AuthenticationPrincipal WanderUser principal,
            @PathVariable String token) {
        return new AcceptedInviteView(invites.accept(principal.id(), token));
    }
}
