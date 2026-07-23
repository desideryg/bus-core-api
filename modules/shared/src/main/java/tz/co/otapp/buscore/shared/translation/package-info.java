/**
 * Message translation: resolving a key and arguments to text in the caller's locale.
 *
 * <p>User-facing text is a key, never a literal, and the locale comes from the request rather than from a
 * server default. A message assembled by concatenation cannot be translated at all — word order differs
 * between languages — so a message with a variable part takes arguments and lets the resolver place them.
 */
package tz.co.otapp.buscore.shared.translation;
