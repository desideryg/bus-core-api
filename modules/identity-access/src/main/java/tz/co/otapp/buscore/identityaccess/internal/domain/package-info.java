/**
 * The module's data shapes: what it stores, what it hands around, and the closed sets it stores and hands
 * around by name.
 *
 * <p>Three subpackages, split by <b>what changes them</b> rather than by what they look like — all three
 * are "just classes with fields", so the useful question is what forces an edit:
 *
 * <ul>
 *   <li>{@code entity} — changes when the <b>schema</b> changes, and only ever alongside a migration.</li>
 *   <li>{@code dto} — changes when a <b>caller's needs</b> change.</li>
 *   <li>{@code enums} — changes when the <b>business vocabulary</b> changes, which is the rarest and the
 *       most expensive, because a persisted constant cannot be renamed without touching stored rows.</li>
 * </ul>
 *
 * <p>Everything here is module-private. A type another module must name does not belong in this tree at
 * all — it belongs at the module's package root, which is the published surface. That is the one boundary
 * ArchUnit enforces, and it is enforced against this whole package tree.
 */
package tz.co.otapp.buscore.identityaccess.internal.domain;
