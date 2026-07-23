/**
 * The module's Spring configuration — and the <b>only</b> package under {@code internal} that the
 * assembler's component scan reaches.
 *
 * <p>{@code BusCoreApplication} scans {@code tz.co.otapp.buscore} but excludes
 * {@code internal.(service|security|api|entity|repository)}. So the assembler finds a configuration class
 * here and nothing else; that configuration then registers this module's own beans, entities, and
 * repositories.
 *
 * <p>The indirection is load-bearing and invisible to ArchUnit, because a component scan crosses a module
 * boundary by <em>string</em> rather than by type reference — no boundary rule sees it. Without the
 * exclusion, a configuration annotated {@code @Profile} would decide nothing: the assembler would already
 * have registered the beans that configuration was gating.
 *
 * <p>Practical consequence: a {@code @Service} that is not reachable from a configuration in this package
 * is not registered. That is the design working, not a bug.
 */
package tz.co.otapp.buscore.identityaccess.internal.config;
