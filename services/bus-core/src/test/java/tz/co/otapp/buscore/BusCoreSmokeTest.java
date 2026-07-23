package tz.co.otapp.buscore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The web layer, proven without a database.
 *
 * <h2>What changed, and why it was not simply deleted</h2>
 *
 * <p>This test used to assert that the whole application boots with nothing running at all. Persistence
 * arrived with identity-access, so that claim is no longer true of the whole application — a database is
 * now a genuine prerequisite, and pretending otherwise would be the dishonest option.
 *
 * <p>But the assertion had already earned its place twice: it caught a stray module dependency that
 * dragged JPA onto the deployable, and it is what would catch the next one. So it is <b>narrowed rather
 * than dropped</b> — from "the application needs nothing" to "the web and error-handling layer needs
 * nothing", which is still worth defending and still catches dependency creep into that path.
 *
 * <p>The {@code no-database} profile excludes the datasource autoconfiguration AND, with it,
 * identity-access — a module that owns tables cannot boot without one. So the routes exercised here are
 * only the assembler's own. Sign-in, and the security chain's refusal shape, belong to
 * {@code StaffLoginIntegrationTest}, against a real PostgreSQL.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("no-database")
class BusCoreSmokeTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("the context starts and the walking-skeleton route answers in the envelope")
    void ping_answers_in_the_envelope() throws Exception {
        // MockMvc drives the DispatcherServlet directly, so the path carries no context-path — over real
        // HTTP this same route is GET /api/ping.
        mockMvc.perform(get("/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.service").value("bus-core"))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    @DisplayName("every key of the envelope is present, including the null ones")
    void envelope_keys_are_always_present() throws Exception {
        // The keys that are null here are the point. A key that is only sometimes present forces every
        // client to test for it, and the first endpoint that omits a DIFFERENT key has invented a second
        // envelope nobody declared.
        mockMvc.perform(get("/ping"))
                .andExpect(jsonPath("$.meta").doesNotExist())
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    @DisplayName("a trace identifier is stamped centrally, without the handler asking")
    void trace_id_is_stamped_on_the_way_out() throws Exception {
        // PingController never sets one. If this passes, the invariant does not depend on any handler
        // remembering — the only way it survives a hundred handlers.
        mockMvc.perform(get("/ping"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("an unknown path is refused by the chain before it can 404")
    void unknown_path_is_refused_not_leaked() throws Exception {
        // With a chain in place, an unauthenticated request to an unknown path is a 401, not a 404 — and
        // that is correct: a 404 would confirm which paths do NOT exist, which is a map of the API for
        // anyone who asks patiently. The 404-rather-than-500 regression is asserted in
        // StaffLoginIntegrationTest, where a real token gets past the chain and reaches the dispatcher.
        mockMvc.perform(get("/definitely-not-a-route"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH.NOT_AUTHENTICATED"));
    }

    @Test
    @DisplayName("a wrong method is a 405 in the envelope")
    void wrong_method_is_reported_as_such() throws Exception {
        mockMvc.perform(post("/ping"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("COMMON.METHOD_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("the web layer still needs no database, broker or object store")
    void web_layer_boots_with_nothing_running() throws Exception {
        // The narrowed form of the original claim. If a future module drags a datasource requirement into
        // the web path, this context fails to start and the failure is here rather than in production.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("actuator is not reshaped by the envelope advice")
    void advice_leaves_foreign_responses_alone() throws Exception {
        // The advice must only touch responses it owns. Wrapping actuator would break every orchestrator
        // probe that expects Boot's own health document.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(jsonPath("$.success").doesNotExist())
                .andExpect(jsonPath("$.traceId").doesNotExist());
    }
}
