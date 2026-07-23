/**
 * The error model: the exception a module throws, and the catalog of codes it may carry.
 *
 * <p>An error code is a <b>stable, machine-readable contract</b>. A client branches on it, a support
 * process routes on it, and a log query counts it — so a code may be added, but its meaning may never be
 * repurposed, and it may not be renamed once released.
 *
 * <p>Codes are grouped by domain and name the condition, not the remedy. Distinct causes get distinct
 * codes: when a caller is refused because they lack a permission, because they are the wrong kind of
 * caller, and because the row belongs to someone else, those are three different problems with three
 * different fixes. A client that cannot tell them apart cannot act on any of them.
 */
package tz.co.otapp.buscore.apicontracts.error;
