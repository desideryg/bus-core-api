/**
 * Module-private internals of {@code fleet}. Nothing outside {@code tz.co.otapp.buscore.fleet} may import from
 * this package tree — the boundary is enforced first by the Maven module graph (a module that does not
 * declare a dependency cannot import the other) and then by ArchUnit's ModuleBoundaryTest. Populate it with
 * {@code api/<audience>}, {@code entity}, {@code repository}, {@code service} (+ {@code impl}),
 * {@code security}, and {@code config} as slices are implemented.
 *
 * <p>The one thing that legitimately crosses this line is the assembler's component scan, which reaches
 * {@code internal.config} by string so a module registers its own beans. See BusCoreApplication.
 */
package tz.co.otapp.buscore.fleet.internal;
