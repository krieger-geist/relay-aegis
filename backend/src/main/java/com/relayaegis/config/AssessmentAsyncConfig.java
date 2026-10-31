package com.relayaegis.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AssessmentAsyncConfig {
    @Bean(name = "assessmentExecutor")
    public Executor assessmentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(8);
        executor.setThreadNamePrefix("relay-assessment-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }

    /**
     * Incident diagnosis and recovery verification run here so a long repository clone can never
     * delay incident handling (and vice versa). Bounded so a burst of incidents cannot exhaust the host.
     */
    @Bean(name = "incidentExecutor")
    public ThreadPoolTaskExecutor incidentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("relay-incident-");
        // Carries the trace and logging context (trace id, incident id) from the caller into the worker thread.
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        return executor;
    }

    /**
     * Gemini calls only (automatic diagnosis and background AI retries). Kept apart from incidentExecutor so a
     * slow or unavailable AI provider can never queue ahead of recovery execution or the incident pipeline.
     * Strictly bounded: 2 threads, 20 queued tasks; overflow falls back to rule-based diagnosis.
     */
    @Bean(name = "aiExecutor")
    public ThreadPoolTaskExecutor aiExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("relay-ai-");
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        return executor;
    }
}
