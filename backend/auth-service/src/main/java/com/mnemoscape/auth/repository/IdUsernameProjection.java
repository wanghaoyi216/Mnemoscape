package com.mnemoscape.auth.repository;

/**
 * Two-column DTO returned by
 * {@link UserRepository#findIdUsernameByIdIn(java.util.Collection)}.
 *
 * <p>Lives as a top-level public type so the JPQL constructor expression
 * {@code new com.mnemoscape.auth.repository.IdUsernameProjection(u.id,
 * u.username)} resolves the same way across every Hibernate version we
 * support (nested types referenced via {@code $} are accepted only by
 * recent Hibernate releases).
 *
 * <p>No Jackson serialisation happens through this type — the controller
 * pivots the {@link java.util.List} into a {@link java.util.Map} and
 * discards everything else. Adding fields here would silently widen the
 * SELECT projection and risk breaking the privacy boundary documented on
 * the repository method.
 */
public record IdUsernameProjection(String id, String username) {
}
