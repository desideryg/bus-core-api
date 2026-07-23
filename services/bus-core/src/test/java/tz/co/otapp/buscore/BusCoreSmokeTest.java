package tz.co.otapp.buscore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The scaffold's behavioural claims: <em>this thing runs</em>, and <em>it speaks the envelope</em>.
 *
 * <p>A reactor that compiles proves less than it looks like it proves. Twenty-seven jars can build green
 * while the assembler fails to start — a component scan that matches nothing, an actuator health group
 * naming a contributor that does not exist, a starter that quietly demands a DataSource. Each is a
 * five-minute fix on the day it appears and an afternoon once real code sits on top, so the claim is pinned
 * down while it is still cheap.
 *
 * <p>Boots the real context under the default {@code api} role and drives real requests through the real
 * dispatcher. Nothing external is required, and nothing external may become required without this test
 * going red.
 */
@SpringBootTest
@AutoConfigureMockMvc
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
        // The keys that are null on this response are the point. A key that is only sometimes present
        // forces every client to test for it, and the first endpoint that omits a DIFFERENT key has
        // invented a second envelope nobody declared.
        mockMvc.perform(get("/ping"))
                .andExpect(jsonPath("$.meta").doesNotExist())
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    @DisplayName("a trace identifier is stamped centrally, without the handler asking")
    void trace_id_is_stamped_on_the_way_out() throws Exception {
        // PingController never sets one. If this passes, the invariant does not depend on any handler
        // remembering — which is the only way it survives a hundred handlers.
        mockMvc.perform(get("/ping"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("an unknown path is a 404 in the envelope, not a 500")
    void unknown_path_is_not_an_internal_error() throws Exception {
        // REGRESSION. The blanket Exception handler originally swallowed Spring's own status-carrying
        // exceptions, so this answered 500 INTERNAL_ERROR and logged a stack trace at ERROR. Every crawler
        // and every typo'd URL would have looked like an outage — and buried the failures that were one.
        mockMvc.perform(get("/definitely-not-a-route"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.statusCode").value(404))
                .andExpect(jsonPath("$.code").value("COMMON.NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("a wrong method is a 405 in the envelope")
    void wrong_method_is_reported_as_such() throws Exception {
        mockMvc.perform(post("/ping"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("COMMON.METHOD_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("health reports UP with no backing service running")
    void health_is_up_with_nothing_else_running() throws Exception {
        // The scaffold must start against an empty machine: no Postgres, no broker, no object store. The
        // day that stops being true it should stop in review, not on someone's first clone.
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
