package tz.co.otapp.buscore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The scaffold's one behavioural claim: <em>this thing runs</em>.
 *
 * <p>An empty reactor that compiles proves less than it looks like it proves. Twenty-seven jars can build
 * green while the assembler fails to start — a component scan that matches nothing, an actuator health
 * group naming a contributor that does not exist, a starter that quietly demands a DataSource. Each of
 * those is a five-minute fix on the day the scaffold is created and an afternoon once real code is on top
 * of it, so the claim is worth pinning down while it is still cheap.
 *
 * <p>Boots the real context under the default {@code api} role and drives a real request through the real
 * dispatcher. Nothing external is required, and nothing external is allowed to become required without
 * this test going red.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BusCoreSmokeTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("the context starts and the walking-skeleton route answers")
    void ping_answers() throws Exception {
        // MockMvc drives the DispatcherServlet directly, so the request path carries no context-path —
        // over real HTTP this same route is GET /api/ping.
        mockMvc.perform(get("/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("bus-core"))
                .andExpect(jsonPath("$.status").value("up"));
    }

    @Test
    @DisplayName("health reports UP without any backing service running")
    void health_is_up_with_nothing_else_running() throws Exception {
        // The scaffold must start against an empty machine: no Postgres, no broker, no object store. The
        // day that stops being true, it should stop being true in a review, not on someone's first clone.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
