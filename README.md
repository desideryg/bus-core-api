# bus-core-api

Unified bus ticketing core. **This is an empty scaffold**: the reactor, the module boundaries, and the
deployable exist and are enforced; no domain code does yet.

Every one of the 27 modules under `modules/` is a skeleton — a `pom.xml` and two `package-info.java`
files. They build as reactor jars so they can be implemented, and the architecture rules already police
them, so the first slice written into any of them is born under the boundaries instead of retrofitted
into them.

## Run it

Nothing external is required. No database, no broker, no cache, no object store.

```bash
mvn install -DskipTests          # ~30s cold
java -jar services/bus-core/target/bus-core.jar
```

```bash
curl localhost:8080/api/ping
# {"service":"bus-core","status":"up","at":"..."}

curl localhost:8080/api/actuator/health
# {"status":"UP","groups":["liveness","readiness"]}
```

Note the `/api` prefix on **both** — `server.servlet.context-path` is global, so the actuator moves with
everything else. A container `HEALTHCHECK` or an orchestrator probe must point at
`/api/actuator/health/readiness`, not `/actuator/health/readiness`.

Dev loop, with devtools restart on recompile:

```bash
mvn -pl services/bus-core -am spring-boot:run
```

## One jar, three roles

`services/bus-core` is the single deployable. Which role it plays is a Spring profile, not a separate
artifact — one build, one version, one image to promote. The roles stay separate *processes* in
production: a retry storm or a settlement batch must never occupy a thread a customer is waiting on.

```bash
java -jar bus-core.jar --spring.profiles.active=api       # HTTP surface on :8080/api  (the default)
java -jar bus-core.jar --spring.profiles.active=worker    # no HTTP at all; sweeps and consumers
java -jar bus-core.jar --spring.profiles.active=gateway   # edge role on :8081/api
```

The artifact is called `bus-core`, not `bus-core-api`: **the artifact names the system, the profile names
the role.** A name ending in `-api` would be lying in two of its three modes. (It also cannot be called
`bus-core-api` — that is the reactor root's own artifactId, and Maven rejects a duplicate coordinate.)

## Layout

```
pom.xml                 reactor root — module list, dependencyManagement, the lombok repackage exclusion
modules/                27 domain modules, all empty skeletons
services/bus-core/      the single deployable
architecture-tests/     ArchUnit rules that police the module DAG and internal-package privacy
```

Inside a module:

```
modules/<module-name>/
├── pom.xml
└── src/main/java/tz/co/otapp/buscore/<modulename>/
    ├── package-info.java     <- the published surface: what other modules may import
    └── internal/             <- api/<audience>, entity, repository, service (+impl), security, config
        └── package-info.java
```

`BusCoreApplication` scans `tz.co.otapp.buscore` but **excludes** everything under
`internal.(service|security|api|entity|repository)` — so the assembler finds each module's
`internal/config` and nothing else, and that configuration registers the module's own beans. If you add a
`@Service` and it is not picked up, that is the design working. The exclusion is load-bearing and
invisible to ArchUnit, because a component scan crosses a boundary by *string*, not by type reference.

Base package is `tz.co.otapp.buscore`. Each module hangs beneath it, its directory name with the dashes
stripped: `modules/seat-inventory` → `tz.co.otapp.buscore.seatinventory`. That mapping is computed by
`ModuleCatalog.packageRootOf`, so a module whose directory and package disagree is invisible to every
architecture rule.

## What is actually enforced

Not much of a scaffold can be wrong yet, which is exactly why the rules go in now — they cost nothing
today and are expensive to retrofit at module fifteen.

| Rule | What it stops |
|---|---|
| `ModuleDependencyTest.the_poms_and_the_declared_dag_agree` | An edge in a pom that nobody decided on, or an edge in the DAG that the build does not have |
| `ModuleDependencyTest.no_module_references_a_module_it_does_not_declare` | Maven leaks a module's whole transitive closure onto its compile classpath. This reads **bytecode**, so an undeclared import fails even though javac allowed it |
| `ModuleDependencyTest.the_dag_is_acyclic` | A cycle, caught in the declaration rather than once Maven trips over it |
| `ModuleBoundaryTest` | Anything outside a module reaching into its `internal/` package |
| `AnalysisClasspathTest` | A module joining the reactor without joining the analysis — where every rule above reports green while checking nothing |
| `BusCoreSmokeTest` | The scaffold quietly acquiring a dependency on something that has to be running |

Run them: `mvn test`.

## Adding a module

Seven files change. All seven, or the module is half-present in a way that stays hidden for months:

1. `pom.xml` (root) — `<module>modules/<name></module>`
2. `pom.xml` (root) — a `dependencyManagement` entry pinning `tz.co.otapp:<name>:0.1.0-SNAPSHOT`
3. `modules/<name>/pom.xml` — parent `bus-core-api`, its internal `<dependency>` edges, starters
4. `modules/<name>/src/main/java/tz/co/otapp/buscore/<pkg>/package-info.java` — what the module is
5. `.../<pkg>/internal/package-info.java` — the hiding notice
6. `architecture-tests/pom.xml` — a `<dependency>` with `<version>${project.version}</version>`
7. `ModuleCatalog.DAG` — the module and its exact edge set

6 and 7 are the ones people forget, and the build catches both: `AnalysisClasspathTest` fails on a missing
6, `ModuleDependencyTest.every_reactor_module_is_in_the_dag` on a missing 7. Keep the
`<version>${project.version}</version>` element on 6 — `AnalysisClasspathTest` matches that exact shape
when it parses the pom.

## Adding a dependency edge

Change **the module's pom and `ModuleCatalog.DAG` in the same commit**, with the reason in the PR. The
build enforces the pairing in both directions.

Two shapes to prefer, both of which keep the DAG acyclic:

- **Point adapters inward.** An adapter implements a port owned by the module it names, and nothing
  depends on the adapter. That is why `payment-gateway-adapter → payment-settlement` is fine and the
  reverse would be a cycle waiting to happen.
- **Hold uids, not foreign keys.** A module that stores another module's identifier as a bare handle, and
  resolves it against nothing, needs no edge at all. `documents` depends on no owner module for exactly
  this reason — which is what lets `fleet`, `tenancy`, `staff`, `agent` and `customer-identity` all
  depend on `documents` without one.

## Deliberately not here yet

- **Persistence.** No JPA, no Flyway, no Postgres driver on `bus-core`'s classpath — no module owns an
  entity or a migration, so adding them would give the scaffold an `EntityManagerFactory` demanding a
  `DataSource` and `mvn spring-boot:run` would fail against a database nobody has started. Flyway is
  already version-pinned in the root pom for the day the first entity lands.
- **Modules in the deployable.** Only `shared` and `api-contracts` are assembled into `bus-core`. A module
  joins when its first vertical slice does — that is the last step of a slice, not the first.
- **Static analysis, Docker, CI.** Deliberate: a SpotBugs exclusion file justified against code that does
  not exist is a liability, not a head start.
