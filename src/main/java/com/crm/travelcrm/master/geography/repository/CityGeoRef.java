package com.crm.travelcrm.master.geography.repository;

/**
 * A city reduced to just its place in the hierarchy — id, name, and the destination and country it
 * sits under. Loaded as a constructor projection so callers that only need to know "are these two
 * cities in the same region?" never hydrate a {@code City} entity or touch its lazy parents.
 *
 * <p>{@code destinationId} is nullable: a city may be created directly under a country and never
 * linked to a destination.
 */
public record CityGeoRef(Long id, String name, Long destinationId, Long countryId) {}
