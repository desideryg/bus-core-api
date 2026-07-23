/**
 * Shared Spring configuration: the persistence setup every module's entities rely on.
 *
 * <p>This is where auditing is enabled, so created and updated timestamps are populated without each module
 * repeating the wiring, and where any converter or type contribution that must exist for every entity is
 * registered.
 *
 * <p>Reachable by the assembler's component scan, unlike a domain module's internals — this module is a
 * library, not a slice, and its configuration is meant to be found.
 */
package tz.co.otapp.buscore.shared.config;
