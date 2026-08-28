package com.wander.weather;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DayWeatherRepository extends JpaRepository<DayWeather, Long> {

    /** Every stored forecast for a trip, in one query — the page asks about all of them. */
    List<DayWeather> findByTripId(Long tripId);
}
