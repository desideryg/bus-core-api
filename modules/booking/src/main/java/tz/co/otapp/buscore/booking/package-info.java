/**
 * Booking lifecycle and idempotent booking creation; the sale-orchestration seam. Coordinates, does not decide.
 *
 * <p>Package root of the {@code booking} module, and its <b>only</b> published surface: a type another
 * module may import lives here, never under {@code internal}. This is a <b>scaffolded skeleton</b> — the
 * module is a reactor artifact so it can be implemented, but it is not yet wired into
 * {@code services/bus-core} and owns no entities, controllers, or migrations.
 *
 * <p>Implement vertical slices under {@code internal/} (api/&lt;audience&gt;, entity, repository, service
 * + impl, security, config). Only this package root is importable by another module.
 */
package tz.co.otapp.buscore.booking;
