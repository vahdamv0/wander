package com.wander.trip;

import java.util.List;

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

import com.wander.security.WanderUser;
import com.wander.trip.dto.AddMemberRequest;
import com.wander.trip.dto.ChangeRoleRequest;
import com.wander.trip.dto.TripMemberView;

import jakarta.validation.Valid;

/**
 * The member list of one trip. A member is addressed by their user id, not by
 * the membership row's.
 *
 * Method names are the operation ids, and ng-openapi-gen exports them
 * unqualified into one barrel file — hence `listMembers` and `addMember` rather
 * than `list` and `add`, which would collide with TripController's.
 */
@RestController
@RequestMapping("/api/trips/{tripId}/members")
public class TripMemberController {

    private final TripMemberService members;

    public TripMemberController(TripMemberService members) {
        this.members = members;
    }

    @GetMapping
    public List<TripMemberView> listMembers(@AuthenticationPrincipal WanderUser principal,
            @PathVariable Long tripId) {
        return members.list(principal.id(), tripId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TripMemberView addMember(@AuthenticationPrincipal WanderUser principal,
            @PathVariable Long tripId,
            @Valid @RequestBody AddMemberRequest request) {
        return members.add(principal.id(), tripId, request);
    }

    @PutMapping("/{userId}/role")
    public TripMemberView changeMemberRole(@AuthenticationPrincipal WanderUser principal,
            @PathVariable Long tripId,
            @PathVariable Long userId,
            @Valid @RequestBody ChangeRoleRequest request) {
        return members.changeRole(principal.id(), tripId, userId, request);
    }

    /** Removes somebody, or leaves the trip when the id is your own. */
    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@AuthenticationPrincipal WanderUser principal,
            @PathVariable Long tripId,
            @PathVariable Long userId) {
        members.remove(principal.id(), tripId, userId);
    }
}
