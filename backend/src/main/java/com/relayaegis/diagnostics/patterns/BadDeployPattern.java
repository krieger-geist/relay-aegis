package com.relayaegis.diagnostics.patterns;

import com.relayaegis.diagnostics.DiagnosticContext;
import com.relayaegis.diagnostics.DiagnosticContext.DeployObservation;
import com.relayaegis.diagnostics.DiagnosticFinding;
import com.relayaegis.diagnostics.EvidenceRef;
import com.relayaegis.diagnostics.FailurePattern;
import com.relayaegis.entity.HealthCheckEntity;
import com.relayaegis.recovery.RecoveryActionType;
import com.relayaegis.runtime.LogExcerpt;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The failure began right after a new image was deployed: the container was recreated on an image other than the
 * last one that passed several consecutive health checks, shortly before the first failed check. Restarting the same
 * bad image would not help, so the recommendation is to roll back to the last good image. The failing container is
 * kept for inspection.
 */
public class BadDeployPattern implements FailurePattern {
    /** A deploy this long (or less) before the first failed check is treated as the likely cause. */
    static final Duration WINDOW = Duration.ofMinutes(30);

    public String id() { return "bad-deploy"; }

    public Optional<DiagnosticFinding> evaluate(DiagnosticContext context) {
        DeployObservation deploy = context.deployment();
        HealthCheckEntity first = context.firstFailure().orElse(null);
        if (deploy == null || first == null || first.checkedAt == null || !context.failing() || !deploy.onDifferentImage() || deploy.createdAt() == null) {
            return Optional.empty();
        }
        Instant created = deploy.createdAt();
        if (created.isAfter(first.checkedAt) || created.isBefore(first.checkedAt.minus(WINDOW))) return Optional.empty();
        if (deploy.goodAt() != null && created.isBefore(deploy.goodAt())) return Optional.empty();

        long minutes = Duration.between(created, first.checkedAt).toMinutes();
        String newImage = (deploy.imageRef() == null || deploy.imageRef().isBlank() ? "" : deploy.imageRef() + " ") + DeployObservation.shortId(deploy.imageId());
        String goodImage = (deploy.goodImageRef() == null || deploy.goodImageRef().isBlank() ? "" : deploy.goodImageRef() + " ") + DeployObservation.shortId(deploy.goodImageId());
        boolean crashing = context.runtime() != null && context.runtime().exists() && (!context.runtime().running() || context.runtime().restarting());

        DiagnosticFinding.Builder finding = DiagnosticFinding.builder(id(), "Bad deploy: new image " + newImage + " fails since its rollout", "deployment")
                .cause("Container " + deploy.container() + " was recreated on image " + newImage + " at " + created + ", "
                        + (minutes < 1 ? "less than a minute" : minutes + " min") + " before the first failed check. The last image that passed "
                        + "consecutive health checks was " + goodImage + ".")
                .explanation("When failures start right after a new release, the release is the most likely cause. Restarting would start the same "
                        + "image again; going back to the last image that worked restores service while the release is fixed.")
                .confidence(crashing ? 0.97 : 0.88)
                .support(new EvidenceRef("DEPLOY-" + deploy.container(), "change", "Deploy: " + deploy.container() + " recreated at " + created
                        + " on " + newImage + "; last good image " + goodImage + (deploy.goodAt() == null ? "" : " (healthy as of " + deploy.goodAt() + ")")))
                .action(RecoveryActionType.ROLLBACK_IMAGE, deploy.container(),
                        "Roll back " + deploy.container() + " to the last good image " + goodImage + ". The failing container is kept, stopped, for inspection; fix the release before redeploying.");
        PatternSupport.citeFailures(finding, context);
        PatternSupport.citeRuntime(finding, context);
        PatternSupport.citeLogs(finding, context, 3, LogExcerpt.LogSignal.Kind.FATAL, LogExcerpt.LogSignal.Kind.EXCEPTION);
        return Optional.of(finding.build());
    }
}
