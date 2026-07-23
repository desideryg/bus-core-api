/**
 * The agent surface: {@code /agent/v1/**}, audience {@code AGENT} or {@code API_CLIENT}.
 *
 * <p><b>No permission expression may appear on any handler in this package.</b> Agents are authorised by
 * approved selling grants in the {@code agent} module, never by RBAC — their permission list is empty by
 * construction, so any permission expression here would deny every agent alive. Authorisation on this
 * surface is the audience gate plus the agent's own grants, and nothing else.
 *
 * <p>Credential-presenting doors (login, PIN exchange, refresh) are public by necessity: each presents its
 * own credential and cannot require a session in order to obtain one. Every such door must be
 * rate-limited and must count its failures — a credential-comparing endpoint that does neither is an
 * enumeration oracle, which is exactly the defect the reference implementation carries on its
 * initial-PIN route.
 */
package tz.co.otapp.buscore.identityaccess.internal.api.agent;
