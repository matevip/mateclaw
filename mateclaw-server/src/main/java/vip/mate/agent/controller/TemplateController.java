package vip.mate.agent.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.model.TemplateDTO;
import vip.mate.agent.service.TemplateService;
import vip.mate.common.result.R;

import java.util.List;

/**
 * Agent 模板接口
 *
 * @author MateClaw Team
 */
@Tag(name = "Agent Templates")
@RestController
@RequestMapping("/api/v1/templates")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateService templateService;

    @Operation(summary = "获取模板列表")
    @GetMapping
    public R<List<TemplateDTO>> list() {
        return R.ok(templateService.listTemplates());
    }

    @Operation(summary = "应用模板创建Agent")
    @PostMapping("/{id}/apply")
    public R<AgentEntity> apply(@PathVariable String id) {
        return R.ok(templateService.applyTemplate(id));
    }
}
