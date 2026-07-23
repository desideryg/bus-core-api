/**
 * The general-purpose audit trail: who changed what, when.
 *
 * <p>Published here are the port a module calls to record a change and the query surface for reading the
 * trail back. The storage behind them is hidden in {@code internal}.
 *
 * <p><b>This is distinct from authentication auditing.</b> An identity module keeps its own record of login
 * attempts, lockouts and token events, because those are security telemetry with their own retention,
 * their own indexes, and rows that must survive a rejected request. Two trails is a deliberate split, not
 * duplication — do not merge them without deciding which of the two sets of requirements loses.
 */
package tz.co.otapp.buscore.shared.audit;
