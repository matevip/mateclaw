package vip.mate.tool.browser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;

/**
 * Browser self-check endpoint. Call this when the browser tool fails — the response
 * tells you exactly what's broken (missing binary, missing libs, broken CDP, etc.)
 * and how to fix it, without needing to inspect server logs.
 */
@Tag(name = "System Health")
@RestController
@RequestMapping("/api/v1/system")
@RequiredArgsConstructor
public class BrowserHealthController {

    private final BrowserDiagnosticsService diagnostics;

    @Operation(summary = "Browser launch diagnostics")
    @GetMapping("/browser-health")
    public R<BrowserDiagnosticsService.Report> getBrowserHealth() {
        return R.ok(diagnostics.run());
    }
}
