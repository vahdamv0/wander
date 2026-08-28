package com.wander.enrich;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PlaceEnrichmentRepository extends JpaRepository<PlaceEnrichment, Long> {

    /** The natural key: one row per place in the world, shared by every trip. */
    Optional<PlaceEnrichment> findByOsmRef(String osmRef);
}
