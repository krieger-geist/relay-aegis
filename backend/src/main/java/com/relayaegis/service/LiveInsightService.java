package com.relayaegis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayaegis.assessment.LiveChanges;
import com.relayaegis.assessment.LiveObservation;
import com.relayaegis.assessment.LiveUrlAnalyzer;
import com.relayaegis.entity.AssessmentEntity;
import com.relayaegis.repository.AssessmentRepository;
import java.net.URI;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Live URL insight for one run: its structured observation (for the timing breakdown) and what changed since the
 * previous completed run of the same endpoint. Read-only; nothing is contacted.
 */
@Service
public class LiveInsightService {
    /** {@code comparison}: COMPARED, FIRST_RUN (no earlier run of this URL), NOT_RECORDED (this or the earlier run predates observation records). */
    public record LiveInsightDto(LiveObservation observation, String comparison, String previousId, Instant previousAt, List<LiveChanges.Change> changes) {}

    private final AssessmentRepository assessments;
    private final ObjectMapper mapper;

    public LiveInsightService(AssessmentRepository assessments, ObjectMapper mapper) {
        this.assessments = assessments;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public LiveInsightDto insight(String id) {
        AssessmentEntity run = assessments.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Assessment not found."));
        if (!isLive(run)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only Live URL investigations have live insight.");
        LiveObservation current = read(run);
        Optional<AssessmentEntity> previous = assessments.findAll().stream()
                .filter(other -> !other.getId().equals(run.getId()) && isLive(other) && "completed".equals(other.getStatus()))
                .filter(other -> other.getCreatedAt() != null && run.getCreatedAt() != null && other.getCreatedAt().isBefore(run.getCreatedAt()))
                .filter(other -> sameEndpoint(other.getApplicationUrl(), run.getApplicationUrl()))
                .max(Comparator.comparing(AssessmentEntity::getCreatedAt));
        if (previous.isEmpty()) return new LiveInsightDto(current, "FIRST_RUN", null, null, List.of());
        LiveObservation earlier = read(previous.get());
        if (current == null || earlier == null) {
            return new LiveInsightDto(current, "NOT_RECORDED", previous.get().getId(), previous.get().getCreatedAt(), List.of());
        }
        return new LiveInsightDto(current, "COMPARED", previous.get().getId(), previous.get().getCreatedAt(), LiveChanges.compare(earlier, current));
    }

    private static boolean isLive(AssessmentEntity run) {
        return ("url".equals(run.getKind()) || "combined".equals(run.getKind())) && run.getApplicationUrl() != null;
    }

    static boolean sameEndpoint(String a, String b) {
        try {
            return a != null && b != null && URI.create(a).getScheme().equalsIgnoreCase(URI.create(b).getScheme()) && LiveUrlAnalyzer.sameEndpoint(a, URI.create(b));
        } catch (Exception ex) {
            return false;
        }
    }

    private LiveObservation read(AssessmentEntity run) {
        if (run.getLiveObservationJson() == null || run.getLiveObservationJson().isBlank()) return null;
        try {
            return mapper.readValue(run.getLiveObservationJson(), LiveObservation.class);
        } catch (Exception ex) {
            return null;
        }
    }
}
