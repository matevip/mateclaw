package vip.mate.system.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.system.service.SystemHealthService;

/**
 * System health check endpoint.
 *
 * @author MateClaw Team
 */
@Tag(name = "System Health")
@RestController
@RequestMapping("/api/v1/system")
@RequiredArgsConstructor
public class SystemHealthController {

    private final SystemHealthService healthService;

    @Operation(summary = "System health check")
    @GetMapping("/health")
    public R<SystemHealthService.HealthResponse> getHealth() {
        return R.ok(healthService.check());
    }
}
