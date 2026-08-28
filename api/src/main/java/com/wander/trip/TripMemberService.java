package com.wander.trip;

import java.util.List;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wander.common.ConflictException;
import com.wander.common.NotFoundException;
import com.wander.sync.TripChanges;
import com.wander.trip.dto.AddMemberRequest;
import com.wander.trip.dto.ChangeRoleRequest;
import com.wander.trip.dto.TripMemberView;
import com.wander.user.User;
import com.wander.user.UserRepository;

/**
 * Who is on a trip, and what they may do.
 *
 * Every method starts with a TripAccessService call, so the two rules that
 * protect every other trip-scoped endpoint hold here too: a non-member gets 404
 * and never learns the trip exists, a member with too weak a role gets 403.
 *
 * One invariant runs through all of it: **a trip has exactly one OWNER.** It is
 * created with the trip and can only ever move, never be added or removed —
 * which is why no method here counts owners. `add` refuses to create a second
 * one, `remove` refuses to delete the only one, and `changeRole` moves it by
 * demoting the caller in the same transaction. Ownership therefore cannot be
 * lost, duplicated, or left to a "last owner" check that someone forgets to
 * write.
 */
@Service
public class TripMemberService {

    private final TripMemberRepository members;
    private final UserRepository users;
    private final TripAccessService access;
    private final TripChanges changes;

    public TripMemberService(TripMemberRepository members, UserRepository users, TripAccessService access,
            TripChanges changes) {
        this.members = members;
        this.users = users;
        this.access = access;
        this.changes = changes;
    }

    /** Any member sees the whole list: you cannot collaborate with people you cannot see. */
    @Transactional(readOnly = true)
    public List<TripMemberView> list(Long userId, Long tripId) {
        access.requireMember(tripId, userId);
        return members.findAllByTripIdOrderByJoinedAtAsc(tripId).stream()
                .map(TripMemberView::of)
                .toList();
    }

    @Transactional
    public TripMemberView add(Long userId, Long tripId, AddMemberRequest request) {
        TripMember owner = access.requireRole(tripId, userId, TripRole.OWNER);
        if (request.role() == TripRole.OWNER) {
            throw new IllegalArgumentException(
                    "A trip has one owner; use the role endpoint to transfer ownership");
        }

        // This does tell a trip's owner whether an address has an account here.
        // That is inherent to adding somebody by email and is accepted: the
        // caller is authenticated and already trusted with the trip. It is not
        // the anonymous enumeration the login and register endpoints guard
        // against by refusing to distinguish "no such user" from "wrong
        // password".
        User invitee = users.findByEmailIgnoreCase(request.email())
                .orElseThrow(() -> new NotFoundException("No account with that email"));

        members.findByTripIdAndUserId(tripId, invitee.getId()).ifPresent(existing -> {
            throw new ConflictException("Already a member of this trip");
        });

        TripMember added = members.save(new TripMember(owner.getTrip(), invitee, request.role()));
        changes.membersChanged(tripId, userId);
        return TripMemberView.of(added);
    }

    @Transactional
    public TripMemberView changeRole(Long userId, Long tripId, Long targetUserId, ChangeRoleRequest request) {
        TripMember caller = access.requireRole(tripId, userId, TripRole.OWNER);
        if (targetUserId.equals(userId)) {
            // An owner steps down by handing the trip to somebody, which is the
            // transfer below. Demoting yourself on your own would leave the trip
            // ownerless.
            throw new IllegalArgumentException("Transfer ownership instead of changing your own role");
        }

        TripMember target = members.findByTripIdAndUserId(tripId, targetUserId)
                .orElseThrow(() -> new NotFoundException("Not a member of this trip"));

        if (request.role() == TripRole.OWNER) {
            // The transfer, both halves in one transaction: for a moment in the
            // middle there would otherwise be two owners or none.
            target.setRole(TripRole.OWNER);
            caller.setRole(TripRole.EDITOR);
        } else {
            target.setRole(request.role());
        }
        // Everybody re-reads: a transfer changed two people's rights, and a
        // demotion to VIEWER has to reach the page that is still showing edit
        // controls.
        changes.membersChanged(tripId, userId);
        return TripMemberView.of(target);
    }

    /**
     * Remove a member, or leave yourself.
     *
     * The gate is `requireMember` rather than `requireRole` because leaving is
     * something any member may do — the role check is on removing *somebody
     * else*.
     */
    @Transactional
    public void remove(Long userId, Long tripId, Long targetUserId) {
        TripMember caller = access.requireMember(tripId, userId);
        // Authorise before looking the target up, so a member who may not remove
        // anybody cannot use the 404 to find out who is on the trip.
        if (!targetUserId.equals(userId) && caller.getRole() != TripRole.OWNER) {
            throw new AccessDeniedException("Only the owner can remove other members");
        }

        TripMember target = members.findByTripIdAndUserId(tripId, targetUserId)
                .orElseThrow(() -> new NotFoundException("Not a member of this trip"));
        if (target.getRole() == TripRole.OWNER) {
            // Covers an owner trying to leave, too: a trip with no owner could
            // never be deleted or shared again. Transferring first is the path.
            throw new ConflictException("Transfer ownership before removing the owner");
        }
        members.delete(target);
        // Naming the person who lost access is what closes their socket — see
        // TripSyncHandler. Membership is checked once, at the handshake, so a
        // removal that left the socket open would leave them being told about
        // changes to a trip they can no longer read.
        changes.accessRevoked(tripId, userId, targetUserId);
    }
}
