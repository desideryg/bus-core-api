/**
 * Booking-discount promotions: authoring, scope/limit validation, and a frozen discount line the sale snapshots. Reduces payable, never baseFare.
 *
 * <p>Package root of the {@code promotions} module, and its <b>only</b> published surface: a type another
 * module may import lives here, never under {@code internal}. This is a <b>scaffolded skeleton</b> — the
 * module is a reactor artifact so it can be implemented, but it is not yet wired into
 * {@code services/bus-core} and owns no entities, controllers, or migrations.
 *
 * <p>Implement vertical slices under {@code internal/} (api/&lt;audience&gt;, entity, repository, service
 * + impl, security, config). Only this package root is importable by another module.
 */
package tz.co.otapp.buscore.promotions;
