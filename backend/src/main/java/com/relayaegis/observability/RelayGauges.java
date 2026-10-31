package com.relayaegis.observability;

import com.relayaegis.repository.ApplicationRepository;
import com.relayaegis.repository.IncidentRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/** Current-state gauges, read from the stored records when Prometheus scrapes. */
@Component
public class RelayGauges implements MeterBinder {
    private final IncidentRepository incidents;
    private final ApplicationRepository applications;

    public RelayGauges(IncidentRepository incidents, ApplicationRepository applications) {
        this.incidents = incidents;
        this.applications = applications;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("relay.incidents.open", incidents, repo -> repo.countByStateNot("recovered"))
                .description("Incidents not yet recovered").register(registry);
        Gauge.builder("relay.applications.monitored", applications, repo -> repo.countByMonitoringEnabledTrue())
                .description("Registered systems with monitoring enabled").register(registry);
    }
}
