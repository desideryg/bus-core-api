package tz.co.otapp.buscore.identityaccess.internal.security;

import java.util.Set;

import tz.co.otapp.buscore.identityaccess.PrincipalType;

/**
 * Which kinds of caller a path prefix is for.
 *
 * <p>The system serves several audiences under one context path, and <b>the prefix is the audience</b>:
 * {@code /admin/v1/**} is for staff, {@code /agent/v1/**} will be for selling agents and machine clients.
 * A caller of the wrong kind is refused at the door, before any permission is consulted.
 *
 * <h2>Why this is a gate of its own</h2>
 *
 * <p>It is tempting to think permissions already cover it: an agent holds no permissions, so it would fail
 * any gated admin route anyway. That is true and it is a <b>coincidence</b> — a fact about an empty
 * collection rather than a decision anybody made. It stops being true the moment a route is exempt from
 * permissions, or an agent principal is ever built with a non-empty set.
 *
 * <p>In the reference implementation this gate did not exist, and agents were kept out of the staff
 * surface only by a <em>tenancy</em> check that happened to reject them — a tenancy check doing an
 * audience check's job, across 57 routes, until somebody noticed.
 *
 * <h2>It lands while there is only one audience</h2>
 *
 * <p>Right now every caller is staff, so this appears to do nothing. That is exactly when it is cheap: the
 * agent surface arrives later already closed, rather than being retrofitted across a surface that has
 * grown in the meantime.
 */
enum Audience {

    /** Staff administration. Humans with roles. */
    STAFF("/admin/v1/", Set.of(PrincipalType.STAFF)),

    /**
     * Selling agents and, later, machine clients.
     *
     * <p>Nothing serves this prefix yet. It is reserved anyway, so that a staff token cannot reach an agent
     * route the day one appears — and so the reservation is a fact rather than an intention.
     */
    AGENT("/agent/v1/", Set.of());

    private final String pathPrefix;
    private final Set<PrincipalType> permitted;

    Audience(String pathPrefix, Set<PrincipalType> permitted) {
        this.pathPrefix = pathPrefix;
        this.permitted = permitted;
    }

    /**
     * The audience a request path belongs to, or null when the path is not audience-scoped.
     *
     * <p>Paths outside the known prefixes — the walking skeleton, actuator — are nobody's audience and are
     * governed by the chain's own rules alone.
     */
    static Audience of(String path) {
        for (Audience audience : values()) {
            if (path.startsWith(audience.pathPrefix)) {
                return audience;
            }
        }
        return null;
    }

    boolean permits(PrincipalType type) {
        return permitted.contains(type);
    }
}
