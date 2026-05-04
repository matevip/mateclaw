package vip.mate.agent.model;

import lombok.Data;

import java.util.List;

/**
 * Agent 模板 DTO
 *
 * @author MateClaw Team
 */
@Data
public class TemplateDTO {

    private String id;
    private String name;
    private String nameZh;
    private String description;
    private String descriptionZh;
    private String icon;
    private String agentType;
    private String tags;
    private Integer maxIterations;
    /**
     * Optional pre-rendered system prompt seeded into the new agent. Templates
     * use H2 sections (## Role / ## Goal / ## Backstory / ## Additional
     * Instructions) so the editor UI can split the prompt into structured
     * fields and derive a one-line tagline for the agent card.
     */
    private String systemPrompt;
    private List<WorkspaceFileTemplate> workspaceFiles;

    @Data
    public static class WorkspaceFileTemplate {
        private String filename;
        private String content;
        private Boolean enabled;
        private Integer sortOrder;
    }
}
