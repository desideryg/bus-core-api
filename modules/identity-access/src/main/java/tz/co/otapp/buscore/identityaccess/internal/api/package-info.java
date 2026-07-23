/**
 * The module's HTTP surface, split into one subpackage per <b>audience</b>.
 *
 * <p>Audience is not a naming convention here — it is a security boundary. {@code admin} serves staff,
 * {@code agent} serves selling agents and machine clients, and the two authenticate differently, carry
 * different principal types, and are gated by different rules. A controller placed in the wrong
 * subpackage is a controller behind the wrong door.
 *
 * <p>Two conventions hold across both:
 *
 * <ul>
 *   <li><b>Mappings are relative.</b> The {@code /api} prefix comes once from
 *       {@code server.servlet.context-path}, never from a controller. Spring Security matchers see the
 *       post-strip path, so a matcher and a mapping are the same literal — which is what lets the two be
 *       compared by eye.</li>
 *   <li><b>Controllers hold no logic.</b> Each is a thin shim over a same-package delegate that resolves
 *       the acting principal and calls a service. The controller's job is the wire format; the delegate's
 *       is the decision.</li>
 * </ul>
 */
package tz.co.otapp.buscore.identityaccess.internal.api;
