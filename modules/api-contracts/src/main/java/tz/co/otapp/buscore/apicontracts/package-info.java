/**
 * The shared wire contract: what a client codes against, and what more than one module must agree on.
 *
 * <p>The other universal leaf. It depends on nothing, and every other module depends on it.
 *
 * <p><b>There is no {@code internal} package here, and there never will be.</b> A domain module hides most
 * of itself because most of it is nobody else's business; this module is <em>entirely</em> other people's
 * business. Everything in it is by definition published, so a hidden package would be a contradiction — and
 * its absence is the structural statement that this module has no implementation of its own.
 *
 * <p>The packages:
 *
 * <ul>
 *   <li>{@code error} — the exception modules throw, and the catalog of codes it may carry</li>
 *   <li>{@code response} — the envelope every endpoint returns: success, error, paged</li>
 *   <li>{@code enums} — wire enums needed by more than one module</li>
 *   <li>{@code security} — the {@code DOMAIN.ACTION} permission constants</li>
 *   <li>{@code paging} — the page request and page metadata shapes</li>
 * </ul>
 *
 * <h2>The admission rule</h2>
 *
 * Appearing on the wire is <b>not</b> sufficient. The test is whether a <em>second</em> module needs the
 * type. A module's own request and response types live in that module, under {@code internal/domain/dto};
 * a type is promoted here when a real second consumer appears, in the commit that introduces it.
 *
 * <p>The reason is blast radius. Every type placed here is depended on by every module in the reactor, so
 * it turns a one-module change into a full rebuild and a shared review surface. A type used by exactly one
 * module pays that cost and buys nothing — and a contracts module that accepts everything becomes a flat
 * namespace of hundreds of types with no owner, where nobody can tell which module a change will break.
 *
 * <p>The opposite mistake is equally real: promote nothing, and two modules quietly grow near-identical
 * view types that drift apart. Promotion on second use is the trade — one deliberate move, at the moment a
 * second consumer proves the type is genuinely shared.
 *
 * <h2>What "contract" obliges</h2>
 *
 * A released error code, enum constant or field name may be <b>added to</b>, never repurposed and never
 * renamed. A client branches on these, a support process routes on them, and a log query counts them.
 * Changing what one means is not a refactor — it is a silent breaking change to every caller, and nothing
 * in this repository will catch it.
 *
 * <p>The envelope shape is a decision this module owns and has not yet taken. Whatever it settles on, it
 * settles once: a surface with two response shapes forces every client to implement both.
 */
package tz.co.otapp.buscore.apicontracts;
