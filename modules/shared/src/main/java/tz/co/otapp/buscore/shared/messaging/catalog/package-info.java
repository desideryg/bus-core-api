/**
 * The event catalog: topic names, event type constants, and the payload shape of each published event.
 *
 * <p>Payloads live here rather than in the publishing module for the same reason a wire DTO is promoted to
 * the contracts module — a consumer must be able to deserialise an event without depending on the module
 * that emitted it. A consumer that had to import the publisher would defeat the point of publishing.
 *
 * <p>Consequence to accept deliberately: this package is a shared contract, so a payload change is a
 * versioning problem, not a refactor. Add fields; do not repurpose them.
 */
package tz.co.otapp.buscore.shared.messaging.catalog;
