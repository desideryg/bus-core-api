package tz.co.otapp.buscore.api;

import java.time.Instant;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The walking skeleton: one route, owned by the assembler rather than by any domain module.
 *
 * <p>Its job is to make "the scaffold runs" a thing you can observe rather than assert. {@code GET
 * /api/ping} answering proves the reactor built, the module jars are on the classpath, the context
 * started, and the servlet container bound its port — which is exactly the set of things that is
 * tedious to debug once twenty modules are in flight and nobody remembers whether the empty tree ever
 * booted on its own.
 *
 * <p>It sits deliberately outside the API contract, and it is expected to be deleted the moment a real
 * audience surface exists. {@code /actuator/health} is the probe endpoint for orchestrators; this one is
 * for humans.
 */
@RestController
public class PingController {

    @GetMapping("/ping")
    Map<String, Object> ping() {
        return Map.of(
                "service", "bus-core",
                "status", "up",
                "at", Instant.now().toString());
    }
}
