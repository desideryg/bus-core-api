/**
 * Operator HRMS: employees, drivers, conductors, departments, attendance, leave, payroll.
 *
 * <p>Package root of the {@code staff} module, and its <b>only</b> published surface: a type another
 * module may import lives here, never under {@code internal}. This is a <b>scaffolded skeleton</b> — the
 * module is a reactor artifact so it can be implemented, but it is not yet wired into
 * {@code services/bus-core} and owns no entities, controllers, or migrations.
 *
 * <p>Implement vertical slices under {@code internal/} (api/&lt;audience&gt;, entity, repository, service
 * + impl, security, config). Only this package root is importable by another module.
 */
package tz.co.otapp.buscore.staff;
