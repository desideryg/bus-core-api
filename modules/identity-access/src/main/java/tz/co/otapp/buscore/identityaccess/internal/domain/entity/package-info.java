/**
 * JPA entities. The schema is owned by migrations, never by Hibernate DDL — the runtime setting is
 * {@code validate}, so an entity and its table must agree exactly or the context fails to start.
 *
 * <p><b>No cross-module foreign key.</b> A row that refers to another module's aggregate holds its
 * {@code uid} as a bare column with no {@code REFERENCES} clause and no JPA association. A constraint
 * would weld this module to another's tables and invert the dependency arrow; a stale handle instead
 * fails closed where it is resolved, and never corrupts a join here.
 *
 * <p>Two identifiers, deliberately. A module-private surrogate key stays inside this package tree, and a
 * {@code uuid} is the only handle allowed to cross a module boundary — which is why no published type in
 * this module carries a {@code Long}.
 */
package tz.co.otapp.buscore.identityaccess.internal.domain.entity;
