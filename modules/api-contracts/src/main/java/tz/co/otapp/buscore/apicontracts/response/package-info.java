/**
 * The response envelope every endpoint returns.
 *
 * <p><b>One shape, on success and on failure alike, with every key present every time.</b> One envelope
 * means a client writes one deserialiser. The moment a second wrapper type exists, the thing telling a
 * client which to expect is no longer in the payload — it is <em>which endpoint they called</em>, and that
 * is not a contract at all.
 *
 * <p>The keys: a success flag, the mirrored HTTP status, a machine-readable code, a human message, the
 * payload, a list of errors, a pagination block, and a trace identifier.
 *
 * <h2>The obligations</h2>
 *
 * <ul>
 *   <li><b>Every key is always present.</b> A key that is sometimes absent forces every client to test for
 *       it, and the first endpoint that omits a different one has invented a second envelope nobody
 *       declared. Present-and-null costs a few bytes and removes the whole class of problem.</li>
 *   <li><b>The success flag is derived from the status, never set by hand.</b> Two fields encoding one fact
 *       will disagree the day somebody sets one and forgets the other.</li>
 *   <li><b>The code is the contract; the message is not.</b> Clients branch on the code, support routes on
 *       it, dashboards count it. The message is human text — reword and localise it freely, and never let a
 *       client have to parse it to learn what happened.</li>
 *   <li><b>Errors is always an array</b>, empty when there is nothing to report — never a map, and never a
 *       key that replaces the payload. A map cannot carry two problems on one field, nor an error that is
 *       not about a field at all.</li>
 *   <li><b>Pagination is nested</b>, so the payload stays a clean array and paging can gain a cursor or an
 *       approximate-total flag without adding top-level keys that mean nothing on most responses.</li>
 *   <li><b>No 204.</b> A void operation returns 200 with a null payload, so the envelope is never absent
 *       and no client needs a second path for "no body".</li>
 * </ul>
 *
 * <p>A validation failure raised by the framework and one thrown by a domain rule are the same thing to a
 * caller and <b>must produce the same response</b>. Where they differ, a client's error handling silently
 * depends on a server-side implementation detail, and will miss every failure raised the other way.
 *
 * <p>The exact field names and JSON shape are specified in the project documentation under API conventions;
 * the types here are that specification made executable. Change them together, and treat a released field
 * name the way a released error code is treated — added to, never repurposed, never renamed.
 */
package tz.co.otapp.buscore.apicontracts.response;
