/**
 * The event envelope and the publisher that puts it on the wire.
 *
 * <p>A module never touches the broker directly, and nothing here is called from a business path — the
 * write path calls {@code outbox} instead, and this package is what drains it. Keeping the two separate is
 * what makes the transactional guarantee possible.
 *
 * <p>Not needed until a module first publishes an event, which is late in the build order. The package
 * exists now so the seam has a home when it arrives.
 */
package tz.co.otapp.buscore.shared.messaging;
