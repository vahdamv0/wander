package com.wander.packing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wander.common.NotFoundException;
import com.wander.packing.dto.PackedRequest;
import com.wander.packing.dto.PackingGroup;
import com.wander.packing.dto.PackingItemRequest;
import com.wander.packing.dto.PackingItemView;
import com.wander.packing.dto.TripPacking;
import com.wander.sync.TripChanges;
import com.wander.trip.TripAccessService;
import com.wander.trip.TripMember;
import com.wander.trip.TripMemberRepository;
import com.wander.trip.TripRole;
import com.wander.trip.dto.TripSummary;
import com.wander.user.User;
import com.wander.user.UserRepository;

/**
 * What to bring, grouped by who is bringing it.
 *
 * The usual gate: `requireMember` to read, `requireRole` to write. A viewer reads
 * the list and ticks nothing — packing is trip content like everything else, and
 * one rule for writes is worth more than an exception for checkboxes.
 */
@Service
public class PackingService {

    private static final TripRole[] CAN_EDIT = { TripRole.OWNER, TripRole.EDITOR };

    private final PackingItemRepository items;
    private final TripMemberRepository members;
    private final UserRepository users;
    private final TripAccessService access;
    private final TripChanges changes;

    public PackingService(PackingItemRepository items, TripMemberRepository members, UserRepository users,
            TripAccessService access, TripChanges changes) {
        this.items = items;
        this.members = members;
        this.users = users;
        this.access = access;
        this.changes = changes;
    }

    @Transactional(readOnly = true)
    public TripPacking list(Long userId, Long tripId) {
        TripMember member = access.requireMember(tripId, userId);
        List<PackingItem> all = items.findForTrip(tripId);
        return group(tripId, TripSummary.of(member.getTrip(), member.getRole()), all);
    }

    @Transactional
    public PackingItemView create(Long userId, Long tripId, PackingItemRequest request) {
        TripMember member = access.requireRole(tripId, userId, CAN_EDIT);
        PackingItem item = new PackingItem(member.getTrip(), request.description().strip(),
                assignee(tripId, request.assigneeUserId()));
        PackingItem saved = items.save(item);
        changes.packingChanged(tripId, userId);
        return PackingItemView.of(saved);
    }

    @Transactional
    public PackingItemView update(Long userId, Long tripId, Long itemId, PackingItemRequest request) {
        access.requireRole(tripId, userId, CAN_EDIT);
        PackingItem item = require(tripId, itemId);
        item.setDescription(request.description().strip());
        // Null is a value here — it moves the item to the shared pile — so this is
        // an assignment rather than a "leave it alone if absent".
        item.setAssignee(assignee(tripId, request.assigneeUserId()));
        changes.packingChanged(tripId, userId);
        return PackingItemView.of(item);
    }

    @Transactional
    public PackingItemView setPacked(Long userId, Long tripId, Long itemId, PackedRequest request) {
        TripMember member = access.requireRole(tripId, userId, CAN_EDIT);
        PackingItem item = require(tripId, itemId);
        // Whoever ticked it, not whoever it belongs to: on a shared item those are
        // different people and the first is the useful one.
        item.setPacked(request.packed(), member.getUser());
        changes.packingChanged(tripId, userId);
        return PackingItemView.of(item);
    }

    @Transactional
    public void delete(Long userId, Long tripId, Long itemId) {
        access.requireRole(tripId, userId, CAN_EDIT);
        items.delete(require(tripId, itemId));
        changes.packingChanged(tripId, userId);
    }

    /**
     * Shared first, then the members in joining order, then anybody who has left
     * but whose items are still on the list.
     *
     * Every current member gets a section even with nothing in it: an absent one
     * reads as missing data, an empty one reads as nothing packed yet.
     */
    private TripPacking group(Long tripId, TripSummary trip, List<PackingItem> all) {
        Map<Long, List<PackingItemView>> byAssignee = new LinkedHashMap<>();
        Map<Long, String> names = new LinkedHashMap<>();
        List<PackingItemView> shared = new ArrayList<>();
        int packed = 0;

        for (PackingItem item : all) {
            if (item.isPacked()) {
                packed++;
            }
            if (item.getAssignee() == null) {
                shared.add(PackingItemView.of(item));
            } else {
                Long id = item.getAssignee().getId();
                names.putIfAbsent(id, item.getAssignee().getDisplayName());
                byAssignee.computeIfAbsent(id, key -> new ArrayList<>()).add(PackingItemView.of(item));
            }
        }

        List<PackingGroup> groups = new ArrayList<>();
        groups.add(new PackingGroup(null, null, true, shared, packedIn(shared)));

        List<Long> current = new ArrayList<>();
        for (TripMember member : members.findAllByTripIdOrderByJoinedAtAsc(tripId)) {
            Long id = member.getUser().getId();
            current.add(id);
            List<PackingItemView> theirs = byAssignee.getOrDefault(id, List.of());
            groups.add(new PackingGroup(id, member.getUser().getDisplayName(), true, theirs,
                    packedIn(theirs)));
        }

        // Somebody who has left keeps their items: quietly reassigning or deleting
        // what a person said they were bringing is worse than a labelled section.
        byAssignee.forEach((id, theirs) -> {
            if (!current.contains(id)) {
                groups.add(new PackingGroup(id, names.get(id), false, theirs, packedIn(theirs)));
            }
        });

        return new TripPacking(trip, groups, all.size(), packed);
    }

    private static int packedIn(List<PackingItemView> items) {
        return (int) items.stream().filter(PackingItemView::packed).count();
    }

    private PackingItem require(Long tripId, Long itemId) {
        return items.findByIdAndTripId(itemId, tripId)
                .orElseThrow(() -> new NotFoundException("Packing item " + itemId + " not found"));
    }

    /**
     * Null stays null — that is the shared pile. Anybody else has to be a member
     * of the trip now: 400 rather than 404, because the id is part of the caller's
     * request and a 404 would answer "does this user exist".
     */
    private User assignee(Long tripId, Long assigneeUserId) {
        if (assigneeUserId == null) {
            return null;
        }
        return members.findByTripIdAndUserId(tripId, assigneeUserId)
                .map(TripMember::getUser)
                .orElseThrow(() -> new IllegalArgumentException(
                        "assigneeUserId: user " + assigneeUserId + " is not a member of this trip"));
    }
}
