package com.bluestaq.notes.team;

import java.util.UUID;

/**
 * Describes a team available to an authenticated user, without exposing its membership list.
 *
 * @param id server-assigned team identifier used in API paths
 * @param name display name of the team
 */
public record Team(UUID id, String name) {
}
