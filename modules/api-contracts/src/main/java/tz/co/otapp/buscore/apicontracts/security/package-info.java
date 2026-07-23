/**
 * The permission catalog: every {@code DOMAIN.ACTION} code, as constants.
 *
 * <p>They live here, rather than in the identity module, for one specific reason: a module gating a route
 * needs the <b>code</b>, not the permission machinery. Keeping the constants in a leaf everything already
 * depends on means a module can declare what a route requires without taking a dependency on identity at
 * all.
 *
 * <p>Every code declared here must also exist in the database seed, and nothing else guarantees that — so
 * assert it both ways in a test. A code that is gated but not seeded refuses <b>everyone</b>, silently and
 * permanently, and no integration test catches it, because tests run as a principal that either holds
 * everything or bypasses the check.
 */
package tz.co.otapp.buscore.apicontracts.security;
