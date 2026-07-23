/**
 * Closed sets: the statuses, kinds, and purposes this module stores and branches on.
 *
 * <p><b>Persist the name, not the ordinal.</b> A persisted ordinal reorders itself the moment a constant is
 * inserted anywhere but the end, silently rewriting the meaning of every stored row — and nothing fails at
 * the time it happens. Map these as strings, and where the database also constrains the column, the check
 * constraint and this enum are two declarations of one set that must be changed together.
 *
 * <p>An enum here is module-private. A constant that appears in another module's code, or on the wire,
 * belongs at the module's package root or in {@code api-contracts} — a status that a client renders is a
 * published contract, whatever package it started in.
 *
 * <p>Renaming a constant is a migration, not a refactor.
 */
package tz.co.otapp.buscore.identityaccess.internal.domain.enums;
