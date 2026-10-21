package com.relayaegis.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** MVC customizations. CORS for /api/** is configured once, in {@link SecurityConfig#corsConfigurationSource()}. */
@Configuration
public class WebConfig implements WebMvcConfigurer {
}
