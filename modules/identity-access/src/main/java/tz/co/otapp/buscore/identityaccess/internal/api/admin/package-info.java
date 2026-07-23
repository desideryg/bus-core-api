/**
 * The staff surface: {@code /admin/v1/**}, audience {@code SYSTEM_USER}.
 *
 * <p>Every handler here is gated. It carries either a permission expression naming a code from the
 * catalog, or an explicit, reviewable exemption — never nothing, and never a bare Spring Security
 * expression. Permission checks go through the published guard bean by <em>name</em>, which is what keeps
 * this module's internals out of every other module's compile classpath.
 *
 * <p><b>Handlers must be {@code public}.</b> Spring's method-security proxy does not advise a
 * package-private method, so a gated handler that is package-private is a silent no-op: the annotation is
 * present, the rule is green, and the door is open. That is not hypothetical — it is the defect an audit
 * of the reference implementation found across every handler it had.
 *
 * <p>A read that returns operator-owned rows must additionally be scoped. Permission answers <em>what</em>
 * may be done; the operator scope answers <em>whose</em> rows it may be done to. Both run, and neither
 * substitutes for the other.
 */
package tz.co.otapp.buscore.identityaccess.internal.api.admin;
