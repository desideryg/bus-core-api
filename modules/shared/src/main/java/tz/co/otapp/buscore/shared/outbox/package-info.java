/**
 * The transactional outbox — the one seam a module uses to announce that something happened.
 *
 * <p>The published port takes a topic, a key and a payload, and writes a row <b>in the caller's own
 * transaction</b>. If the business write commits, the event commits with it; if it rolls back, so does the
 * event. A relay publishes committed rows afterwards.
 *
 * <p>That ordering is the entire point. Publishing to a broker from inside a transaction produces the
 * failure this design exists to prevent: the broker accepts, the database rolls back, and the rest of the
 * system now believes something that never happened. There is no way to make that atomic across two
 * systems, so the event is made part of the one transaction that can be.
 *
 * <p>Delivery is <b>at least once</b>. A consumer must be idempotent: the same event will arrive twice, and
 * a retry must not issue a second ticket or credit a wallet again.
 */
package tz.co.otapp.buscore.shared.outbox;
