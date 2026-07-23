/**
 * Implementations of this module's service interfaces.
 *
 * <p>This is where the flows live — the ordering inside them is frequently the security property, not an
 * implementation detail. Two that are easy to get wrong:
 *
 * <ul>
 *   <li><b>Check the lockout before checking the secret.</b> A lock that still evaluates the presented
 *       credential stops nothing, because the attacker's last guess is the one that matters.</li>
 *   <li><b>Consume a superseded single-use token, and flush, before inserting its replacement.</b> Where
 *       the schema permits only one live token per subject, the two are momentarily both live otherwise
 *       and the insert is rejected.</li>
 * </ul>
 *
 * <p>Nothing here is registered by the assembler's component scan; the module's configuration registers
 * it. See the {@code config} package.
 */
package tz.co.otapp.buscore.identityaccess.internal.service.impl;
