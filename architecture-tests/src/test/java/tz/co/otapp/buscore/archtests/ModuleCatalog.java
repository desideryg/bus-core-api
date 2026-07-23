package tz.co.otapp.buscore.archtests;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The module DAG, written down once so every rule in this package reads the same map.
 *
 * <p>Every edge here is deliberate; adding one is an architectural decision, not a build fix. To add a
 * dependency: change the module's pom <em>and</em> this map, in the same commit, with the reason in the
 * PR. {@link ModuleDependencyTest} enforces that pairing in both directions and then goes further — it
 * reads bytecode, because Maven puts a module's transitive closure on its compile classpath and the
 * compiler will happily let a module import something its pom never named.
 *
 * <p>Every module may depend on {@code shared} and {@code api-contracts}. <b>Only the latter is a leaf</b>
 * — {@code shared} depends on it, so the two are a chain rather than a pair, and their in-degrees differ
 * (26 and 25). They are listed explicitly rather than special-cased, because a special case is a place a
 * future edge hides.
 */
final class ModuleCatalog {

    private ModuleCatalog() {
    }

    /** Module name to the set of module names its pom may declare. Insertion order is build-spine order. */
    static final Map<String, Set<String>> DAG = new LinkedHashMap<>();

    static {
        // The foundation. api-contracts is the true leaf: pure types, no dependency of any kind, not even
        // on a framework — so the same types can back a generated client SDK with no Spring on its
        // classpath.
        DAG.put("api-contracts", Set.of());
        // shared is machinery, and machinery depends on contracts. The edge exists because shared must be
        // able to refuse: paging validation rejects an unusable sort, and it does so with the one error
        // model rather than a JDK exception the web layer would have to blanket-map to 400 — which would
        // turn ordinary programming mistakes into 400s for callers instead of 500s in the log.
        DAG.put("shared", Set.of("api-contracts"));

        // Tier 1 — identity, the byte store, and the reference data everything else names. These modules
        // name nothing above themselves.
        DAG.put("identity-access", Set.of("shared", "api-contracts"));
        DAG.put("storage", Set.of("shared", "api-contracts", "identity-access"));
        // documents layers ON TOP of storage: documents owns what a file MEANS (owner, type, expiry,
        // verification), storage owns where the bytes are.
        //
        // THE EDGE THAT IS NOT HERE IS THE POINT: documents depends on NO owner module — not fleet, not
        // tenancy, not agent, not staff. It holds ownerUid as a bare handle and resolves it against
        // nothing. That absence is what lets every one of those modules depend on documents without a
        // cycle. Adding an owner-module edge here to "look up an owner's name" would make that impossible
        // forever; a read-side label belongs in the owner module's own view.
        DAG.put("documents", Set.of("shared", "api-contracts", "identity-access", "storage"));
        DAG.put("tenancy", Set.of("shared", "api-contracts", "identity-access", "documents"));
        DAG.put("network", Set.of("shared", "api-contracts", "identity-access"));
        DAG.put("fleet", Set.of("shared", "api-contracts", "identity-access", "documents"));
        DAG.put("staff", Set.of("shared", "api-contracts", "identity-access", "tenancy", "documents"));
        DAG.put("quota", Set.of("shared", "api-contracts"));
        DAG.put("exchange-rate", Set.of("shared", "api-contracts"));
        // promotions holds only uid handles (operator, route, booking, agent) and depends on NO sale-path
        // module, which is what keeps booking -> promotions acyclic.
        DAG.put("promotions", Set.of("shared", "api-contracts", "identity-access"));
        DAG.put("reporting", Set.of("shared", "api-contracts"));
        DAG.put("accounting-ledger", Set.of("shared", "api-contracts"));
        // customer is the passenger golden record, and platform-global on purpose: no row here
        // carries an operator_uid. No tenancy or agent edge — naming a tenant would re-fracture the
        // traveller into per-operator identities, which is the whole thing this module exists to prevent.
        DAG.put("customer", Set.of("shared", "api-contracts", "identity-access", "documents"));

        // Tier 2 — the selling identity, the timetable, the price, and the seat.
        DAG.put("agent",
                Set.of("shared", "api-contracts", "identity-access", "tenancy", "network", "documents"));
        DAG.put("scheduling",
                Set.of("shared", "api-contracts", "identity-access", "network", "fleet", "tenancy", "staff"));
        DAG.put("fare",
                Set.of("shared", "api-contracts", "identity-access", "network", "scheduling", "tenancy", "fleet"));
        DAG.put("seat-inventory", Set.of("shared", "api-contracts", "identity-access", "agent", "scheduling",
                "network", "fare", "fleet"));
        DAG.put("wallet-ledger",
                Set.of("shared", "api-contracts", "identity-access", "agent", "tenancy"));

        // Tier 3 — the sale itself.
        DAG.put("booking", Set.of("shared", "api-contracts", "identity-access", "agent", "scheduling", "fare",
                "seat-inventory", "quota", "network", "tenancy", "promotions", "customer"));
        DAG.put("payment-settlement", Set.of("shared", "api-contracts", "identity-access", "booking", "agent",
                "wallet-ledger", "scheduling", "promotions"));
        DAG.put("notification", Set.of("shared", "api-contracts", "identity-access", "booking", "payment-settlement",
                "wallet-ledger", "scheduling", "network", "tenancy", "agent"));

        // Adapters point INWARD: each implements a port owned by the module it names, and nothing depends
        // on them. A reverse edge would weld a money-critical module to a concrete vendor.
        DAG.put("payment-gateway-adapter",
                Set.of("shared", "api-contracts", "payment-settlement", "wallet-ledger"));
        DAG.put("uts-adapter", Set.of("shared", "api-contracts", "notification", "booking"));
        DAG.put("fiscal-adapter", Set.of("shared", "api-contracts", "notification", "payment-settlement"));

        DAG.put("charter", Set.of("shared", "api-contracts", "booking", "scheduling", "seat-inventory", "fare",
                "fleet", "network", "agent"));
    }

    /** The reactor root's own artifactId. It appears as every module's {@code <parent>}, never as an edge. */
    static final String REACTOR_ROOT = "bus-core-api";

    /** Package root per module: the name with its dashes stripped ({@code seat-inventory} -> {@code seatinventory}). */
    static String packageRootOf(String module) {
        return "tz.co.otapp.buscore." + module.replace("-", "").toLowerCase(Locale.ROOT);
    }
}
