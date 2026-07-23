/**
 * The paging contract: what a caller sends to request a page, and what the envelope reports back.
 *
 * <p>These are the wire shapes only. The logic that validates and normalises a page request lives in the
 * shared kernel — this package is what a client codes against.
 *
 * <p>Report total counts as what they are. If a total is expensive and therefore approximate or omitted on
 * some endpoints, that must be visible in the contract rather than left for a client to discover when their
 * pagination controls misbehave.
 */
package tz.co.otapp.buscore.apicontracts.paging;
