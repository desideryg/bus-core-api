/**
 * Cross-cutting kernel: the machinery every module needs and no module should implement twice.
 *
 * <p>One of the two universal leaves. It depends on nothing, and every other module depends on it — which
 * makes the bar for admission high. The test is <b>"is this machinery every module needs?"</b>, not "where
 * else would it go". A facility used by one module belongs in that module; putting it here to avoid a
 * decision makes every other module rebuild for a change that concerns one.
 *
 * <p><b>This module is shaped differently from a domain module, on purpose.</b> A domain module is one
 * bounded concern with a single {@code internal} tree behind a single published surface. This is a library
 * of several independent facilities, so each owns its package and hides its implementation in its own
 * {@code internal} beneath it — {@code audit}, {@code outbox} and {@code messaging} each publish a port and
 * conceal what backs it. There is deliberately no top-level {@code internal} here, because there is no
 * single implementation to hide.
 *
 * <p>The facilities:
 *
 * <ul>
 *   <li>{@code abstraction} — the base entity, and with it the id/uid contract the whole codebase rests on</li>
 *   <li>{@code config} — persistence and auditing wiring no module should repeat</li>
 *   <li>{@code audit} — the general-purpose change trail</li>
 *   <li>{@code logging} — neutralising caller-controlled values before they reach a log line</li>
 *   <li>{@code outbox} — the transactional seam for announcing that something happened</li>
 *   <li>{@code messaging} — the envelope and publisher that drain the outbox, plus the event catalog</li>
 *   <li>{@code paging} — one implementation of page, size and sort, for every list endpoint</li>
 *   <li>{@code time} — UTC everywhere, and a clock a test can move</li>
 *   <li>{@code translation} — user-facing text as keys resolved against the caller's locale</li>
 * </ul>
 *
 * <p>They are not all needed at once. {@code abstraction}, {@code config}, {@code time} and {@code paging}
 * are wanted by the first slice that persists anything; {@code outbox} and {@code messaging} are not needed
 * until a module first publishes an event, which is late in the build order. The packages exist now so each
 * seam has a settled home rather than being invented under pressure.
 *
 * <p><b>This module will own migrations.</b> It is a library, but not only a library: the base entity's
 * column shape has to exist in the database before any module's first table can extend it.
 */
package tz.co.otapp.buscore.shared;
