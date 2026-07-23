/**
 * Service interfaces — the module's internal seams, one per coherent responsibility.
 *
 * <p>Kept narrow on purpose. There is no single module-wide facade: a caller that only needs to verify a
 * transaction PIN must not compile against session management or credential reset, and one interface
 * carrying all of it guarantees that it does.
 *
 * <p>Two rules govern every credential-comparing method:
 *
 * <ul>
 *   <li><b>The identifier is never an oracle.</b> An unknown identifier, a wrong secret, and a non-active
 *       account all answer identically. A distinguishable refusal tells an attacker which accounts
 *       exist.</li>
 *   <li><b>Failure side effects must survive the rejection.</b> A lockout counter, an attempt count, and
 *       an audit row are written on the failing path — so the transaction must be configured not to roll
 *       back on the exception that rejects the request. A plain transaction discards all three along with
 *       the throw, silently disabling lockout and erasing the forensic trail.</li>
 * </ul>
 */
package tz.co.otapp.buscore.identityaccess.internal.service;
