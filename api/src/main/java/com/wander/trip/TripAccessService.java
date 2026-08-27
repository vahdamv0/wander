package com.wander.trip;

import java.util.Set;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wander.common.NotFoundException;

/**
 * The one gate every trip-scoped operation goes through.
 *
 * Two rules, and the first one is the subtle one:
 *
 *  - A non-member gets 404, not 403. A 403 confirms the trip exists, which lets
 *    anyone walk the id space and count trips; 404 makes "not yours" and "not
 *    there" indistinguishable from outside.
 *  - A member whose role is too weak gets 403. They already know the trip
 *    exists, so there is nothing left to leak.
 */
@Service
public class TripAccessService {

    private final TripMemberRepository members;

    public TripAccessService(TripMemberRepository members) {
        this.members = members;
    }

    @Transactional(readOnly = true)
    public TripMember requireMember(Long tripId, Long userId) {
        return members.findByTripIdAndUserId(tripId, userId)
                .orElseThrow(() -> new NotFoundException("Trip " + tripId + " not found"));
    }

    @Transactional(readOnly = true)
    public TripMember requireRole(Long tripId, Long userId, TripRole... allowed) {
        TripMember member = requireMember(tripId, userId);
        if (!Set.of(allowed).contains(member.getRole())) {
            throw new AccessDeniedException("Requires one of " + Set.of(allowed));
        }
        return member;
    }
}
