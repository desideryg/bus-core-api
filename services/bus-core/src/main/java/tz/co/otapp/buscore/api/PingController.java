package tz.co.otapp.buscore.api;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import tz.co.otapp.buscore.apicontracts.response.ApiResponse;
import tz.co.otapp.buscore.shared.time.Times;

/**
 * The walking skeleton: one route, owned by the assembler rather than by any domain module.
 *
 * <p>Its job is to make "the scaffold runs" observable rather than asserted. {@code GET /api/ping}
 * answering proves the reactor built, the module jars are on the classpath, the context started, the
 * servlet container bound its port, <em>and</em> that a response leaves wrapped in the envelope with a
 * trace identifier stamped on it — which is the one part no unit test can demonstrate.
 *
 * <p>It sits deliberately outside the API contract and is expected to be deleted once a real audience
 * surface exists. {@code /actuator/health} is the probe endpoint for orchestrators; this one is for humans
 * and for proving the response pipeline.
 */
@RestController
public class PingController {

    @GetMapping("/ping")
    ApiResponse<Map<String, Object>> ping() {
        // No traceId here, on purpose: the advice stamps it on the way out. A handler that had to remember
        // would eventually be a handler that forgot.
        return ApiResponse.ok(Map.of(
                "service", "bus-core",
                "at", Times.now().toString()));
    }
}
