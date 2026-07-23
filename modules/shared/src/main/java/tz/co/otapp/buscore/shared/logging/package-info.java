/**
 * Log hygiene.
 *
 * <p>Chiefly: neutralising caller-controlled values before they reach a log line. A value a user supplied
 * can carry carriage returns and newlines, and a log that concatenates it without neutralising them lets a
 * caller forge entire log entries — the record you would rely on during an incident becomes the thing the
 * attacker wrote.
 *
 * <p>Keep the sanitiser in one place. Where a static-analysis tool is configured to trust it as a cleanser,
 * that configuration names this class, and a second copy elsewhere would not be recognised.
 */
package tz.co.otapp.buscore.shared.logging;
