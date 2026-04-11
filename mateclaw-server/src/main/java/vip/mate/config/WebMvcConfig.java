package vip.mate.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置（跨域、拦截器等）
 *
 * @author MateClaw Team
 */
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties({GraphObservationProperties.class, ConversationWindowProperties.class, ToolTimeoutProperties.class})
public class WebMvcConfig implements WebMvcConfigurer {

    private final WorkspaceAccessInterceptor workspaceAccessInterceptor;

    /** CORS allowed origins, comma-separated. Default "*" for dev, restrict in production. */
    @Value("${mateclaw.cors.allowed-origins:*}")
    private String allowedOrigins;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(workspaceAccessInterceptor)
                .addPathPatterns("/api/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(allowedOrigins.split(","))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
