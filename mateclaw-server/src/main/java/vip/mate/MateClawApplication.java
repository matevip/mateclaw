package vip.mate;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * MateClaw - Personal AI Assistant
 * Powered by Spring AI Alibaba
 *
 * @author MateClaw Team
 */
@SpringBootApplication(exclude = {
    // 禁用 Spring AI MCP Client 自动配置（由 McpClientManager 自行管理生命周期）
    org.springframework.ai.mcp.client.common.autoconfigure.McpClientAutoConfiguration.class,
    org.springframework.ai.mcp.client.common.autoconfigure.McpToolCallbackAutoConfiguration.class,
    org.springframework.ai.mcp.client.common.autoconfigure.StdioTransportAutoConfiguration.class,
    org.springframework.ai.mcp.client.common.autoconfigure.annotations.McpClientAnnotationScannerAutoConfiguration.class,
    org.springframework.ai.mcp.client.httpclient.autoconfigure.SseHttpClientTransportAutoConfiguration.class,
    org.springframework.ai.mcp.client.httpclient.autoconfigure.StreamableHttpHttpClientTransportAutoConfiguration.class,
})
@EnableScheduling
@MapperScan("vip.mate.**.repository")
public class MateClawApplication {

    public static void main(String[] args) {
        SpringApplication.run(MateClawApplication.class, args);
    }

    /**
     * MyBatis Plus pagination plugin.
     *
     * <p>DbType is auto-detected from the JDBC connection at runtime rather
     * than hardcoded. Hardcoding H2 here meant the MySQL deployment used
     * the H2 dialect for the count query, which silently returned 0 —
     * frontends saw records but total=0 and couldn't paginate (RFC-042 P0).
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
        return interceptor;
    }
}
