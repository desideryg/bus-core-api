/**
 * Data transfer shapes: request and response bodies, and the projections a query returns.
 *
 * <p>These are module-private. A shape another module consumes belongs at the module's package root
 * instead — putting it here and importing it across a boundary is the exact leak the {@code internal}
 * split exists to prevent, and ArchUnit fails the build on it.
 *
 * <p><b>A DTO carries data, never authority.</b> The acting principal, the operator being acted for, and
 * anything derived from either are resolved server-side from the authenticated token. A field on a request
 * body that names who the caller is, or whose rows they are touching, is a field a caller can forge — and
 * a request body is the one place authority must never come from.
 *
 * <p>Prefer records, and keep the wire shape separate from the entity even when the two are momentarily
 * identical. They diverge the first time a column is added that a caller must not see; a response that is
 * an entity has no way to withhold it.
 */
package tz.co.otapp.buscore.identityaccess.internal.domain.dto;
