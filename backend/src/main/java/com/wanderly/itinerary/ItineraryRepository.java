package com.wanderly.itinerary;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ItineraryRepository extends JpaRepository<Itinerary, UUID> {

    List<Itinerary> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<Itinerary> findByIdAndUserId(UUID id, UUID userId);

    /** Trips starting on a given day: input for the day-before reminder. */
    List<Itinerary> findByStartDate(LocalDate startDate);
}
