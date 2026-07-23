/**
 * Time handling, standardised.
 *
 * <p>Everything is stored and compared in UTC. Timestamps are written as instants without a zone offset,
 * and a local wall-clock time is resolved at query time against the zone that the reader cares about —
 * never by storing a zoned value and hoping every writer used the same zone.
 *
 * <p>Obtaining "now" goes through this package rather than a direct call, so a test can control it. A flow
 * with an expiry, a lockout window or a rotation deadline cannot be tested honestly against a clock it
 * cannot move.
 */
package tz.co.otapp.buscore.shared.time;
