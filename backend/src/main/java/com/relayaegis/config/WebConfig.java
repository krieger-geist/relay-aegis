package com.relayaegis.config;

import com.relayaegis.cluster.LeaderForwardingInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** MVC customizations. CORS for /api/** is configured once, in {@link SecurityConfig#corsConfigurationSource()}. */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final LeaderForwardingInterceptor leaderForwarding;

    public WebConfig(LeaderForwardingInterceptor leaderForwarding) { this.leaderForwarding = leaderForwarding; }

    /** Requests that make RELAY act run on the leader; see {@link LeaderForwardingInterceptor}. */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(leaderForwarding).addPathPatterns("/api/incidents/*/recover", "/api/applications/*/checks");
    }
}
