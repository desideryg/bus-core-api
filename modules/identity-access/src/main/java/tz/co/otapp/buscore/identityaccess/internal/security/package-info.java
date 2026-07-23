/**
 * Authentication machinery: the filter chain, token minting and parsing, and the hashers.
 *
 * <p><b>Hashing follows how a secret is used, not what it is.</b> A secret that is <em>looked up</em>
 * (a refresh token, a credential token, an API key) must be hashed deterministically, so the lookup is one
 * indexed read. A secret that is <em>verified</em> against a presented value (a password, a PIN, an API
 * secret) must use a slow adaptive hash with a per-row salt. A secret that must be <em>recomputed</em>
 * (a TOTP seed) cannot be hashed at all and is encrypted at rest instead.
 *
 * <p>Getting that backwards does not fail loudly. An adaptive hash produces a different value on every
 * call, so a lookup keyed on one matches nothing, ever — authentication simply stops working for reasons
 * that look like anything but the hash.
 *
 * <p>Filters populate the security context and clear it on any failure rather than throwing, so they
 * compose without knowing about one another and a malformed credential is indistinguishable from an
 * absent one.
 */
package tz.co.otapp.buscore.identityaccess.internal.security;
