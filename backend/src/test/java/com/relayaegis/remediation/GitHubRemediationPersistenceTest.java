package com.relayaegis.remediation;

import static org.assertj.core.api.Assertions.assertThat;

import com.relayaegis.entity.GitHubRemediationEntity;
import com.relayaegis.repository.GitHubRemediationRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Live regression: V8 declared validation_status VARCHAR(24) but "preexisting_test_failures" has 25 characters, so the
 * first audit insert failed in PostgreSQL. Other tests let Hibernate create the schema from the entities, which hid the
 * mismatch; this one builds the schema only from the real Flyway migrations and persists every value the workflow writes.
 */
@SpringBootTest(properties = {
        "relay.monitoring.initial-delay-ms=3600000",
        "spring.datasource.url=jdbc:h2:mem:relay-migrations-" + "github;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none"})
class GitHubRemediationPersistenceTest {
    @Autowired GitHubRemediationRepository remediations;

    @Test
    void everyStatusAndValidationValueFitsTheMigratedSchema() {
        List<String> statuses = List.of(GitHubRemediationEntity.APPLYING, GitHubRemediationEntity.BRANCH_CREATED,
                GitHubRemediationEntity.COMMITTED, GitHubRemediationEntity.PR_CREATED, GitHubRemediationEntity.FAILED);
        List<String> validations = List.of(GitHubRemediationService.VALIDATION_PASSED, GitHubRemediationService.VALIDATION_PREEXISTING);
        for (String status : statuses) {
            for (String validation : validations) {
                GitHubRemediationEntity record = new GitHubRemediationEntity();
                record.id = "GHR-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
                record.repairJobId = "PATCH-017D2D71E0";
                record.assessmentId = "ASM-15A38977-997";
                record.findingId = "F-0765a1f2";
                record.userId = UUID.randomUUID().toString();
                record.repository = "krieger-geist/relay-aegis-remediation-test";
                record.baseBranch = "main";
                record.baseSha = "5b8e3640c1f44b9563494d316fed5117ca078aa4";
                record.branch = "relay-aegis/fix/F-0765a1f2-20";
                record.commitSha = "0123456789abcdef0123456789abcdef01234567";
                record.prNumber = 1;
                record.prUrl = "https://github.com/krieger-geist/relay-aegis-remediation-test/pull/1";
                record.status = status;
                record.failedStage = "pull_request_lookup";
                record.errorCode = "insufficient_permissions";
                record.errorMessage = "x".repeat(1000);
                record.validationStatus = validation;
                record.preexistingFailuresAcknowledged = true;
                record.verificationStatus = "not_run";
                record.verificationDetail = "y".repeat(1200);
                record.createdAt = Instant.now();
                record.updatedAt = record.createdAt;
                remediations.saveAndFlush(record);
            }
        }
        assertThat(remediations.findByRepairJobIdOrderByCreatedAtDesc("PATCH-017D2D71E0")).hasSize(statuses.size() * validations.size());
    }
}
