/**
 * One implementation of page, size and sort handling, for every list endpoint in the system.
 *
 * <p>It exists to be the only one. Where two modules normalise paging separately they disagree about the
 * maximum page size, about whether an unknown sort field is ignored or refused, and about the error a
 * malformed request produces — so the same bad input gets two different answers depending on which door it
 * arrived at. That is a contract defect, and it is invisible until a client hits both.
 *
 * <p>An unknown sort property must be <b>refused</b>, not silently dropped: silently returning
 * arbitrarily-ordered results to a caller who asked for an order is worse than an error, because nothing
 * looks wrong.
 */
package tz.co.otapp.buscore.shared.paging;
