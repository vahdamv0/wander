package com.wander.trip;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wander.common.NotFoundException;
import com.wander.trip.dto.CreateTripRequest;
import com.wander.trip.dto.TripSummary;
import com.wander.user.User;
import com.wander.user.UserRepository;

@Service
public class TripService {

    private final TripRepository trips;
    private final TripMemberRepository members;
    private final UserRepository users;
    private final TripAccessService access;

    public TripService(TripRepository trips, TripMemberRepository members, UserRepository users,
            TripAccessService access) {
        this.trips = trips;
        this.members = members;
        this.users = users;
        this.access = access;
    }

    @Transactional
    public TripSummary create(Long userId, CreateTripRequest request) {
        if (request.endDate().isBefore(request.startDate())) {
            throw new IllegalArgumentException("endDate must not be before startDate");
        }
        User creator = users.findById(userId)
                .orElseThrow(() -> new NotFoundException("User " + userId + " not found"));

        Trip trip = trips.save(new Trip(request.name(), request.destination(), request.startDate(),
                request.endDate()));
        // Creating the trip and the owner membership in one transaction: a trip
        // with no members would be invisible to everyone, including its author.
        members.save(new TripMember(trip, creator, TripRole.OWNER));
        return TripSummary.of(trip, TripRole.OWNER);
    }

    @Transactional(readOnly = true)
    public List<TripSummary> listForUser(Long userId) {
        List<Trip> visible = trips.findAllForUser(userId);
        // One membership lookup for the whole page rather than per trip.
        Map<Long, TripRole> roles = visible.stream()
                .map(trip -> members.findByTripIdAndUserId(trip.getId(), userId).orElseThrow())
                .collect(Collectors.toMap(m -> m.getTrip().getId(), TripMember::getRole,
                        (a, b) -> a));
        return visible.stream()
                .map(trip -> TripSummary.of(trip, roles.get(trip.getId())))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public TripSummary get(Long userId, Long tripId) {
        TripMember member = access.requireMember(tripId, userId);
        return TripSummary.of(member.getTrip(), member.getRole());
    }

    @Transactional
    public void delete(Long userId, Long tripId) {
        // Owner only; any other member gets 403, a non-member 404.
        TripMember member = access.requireRole(tripId, userId, TripRole.OWNER);
        // Memberships go with it: trip_members declares ON DELETE CASCADE, so
        // deleting rows here first would just duplicate what the database does.
        trips.delete(member.getTrip());
    }
}
