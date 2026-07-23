/**
 * Enums that appear on the wire and are needed by more than one module.
 *
 * <p>The bar for living here is deliberately high: an enum used by exactly one module belongs in that
 * module, under {@code internal/domain/enums}. It is promoted here when a second consumer genuinely
 * appears — most often because a client must render a status the owning module produces — and it is moved
 * in the commit that introduces that consumer.
 *
 * <p>Once here, a constant's <b>name is the contract</b>. It is persisted by name and parsed by name, so a
 * constant may be appended but never renamed or reordered. A display label is a separate field and may be
 * reworded freely.
 */
package tz.co.otapp.buscore.apicontracts.enums;
