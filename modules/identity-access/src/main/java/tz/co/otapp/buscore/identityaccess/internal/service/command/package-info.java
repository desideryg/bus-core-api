/**
 * Command objects: the multi-field inputs to service calls.
 *
 * <p>A command names its fields, so a call site cannot silently transpose two arguments of the same type —
 * which, on this module's surface, means swapping one credential for another.
 *
 * <p>Commands carry data the caller is entitled to supply. They never carry <em>authority</em>: the acting
 * principal, the operator being acted for, and anything derived from either are resolved server-side from
 * the authenticated token. A field on a command that names who the caller is, is a field a caller can
 * forge.
 */
package tz.co.otapp.buscore.identityaccess.internal.service.command;
