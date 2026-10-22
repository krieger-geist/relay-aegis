package com.relayaegis.diagnostics.patterns;

import com.relayaegis.diagnostics.DiagnosticContext;
import com.relayaegis.diagnostics.DiagnosticFinding;
import com.relayaegis.diagnostics.FailurePattern;
import com.relayaegis.recovery.RecoveryActionType;
import com.relayaegis.runtime.LogExcerpt;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The application is failing and its logs show a dependency (usually the database) rejecting its credentials. The
 * dependency is up and answering, so no runtime action helps: a restart reconnects with the same credential. The fix is
 * in the credential source (code or configuration), followed by a redeploy.
 */
public class DependencyCredentialsRejectedPattern implements FailurePattern {
    public static final String ID = "dependency-credentials-rejected";
    private static final Pattern USER = Pattern.compile("(?i)for user\\s+\"?'?([A-Za-z0-9_.@-]{1,64})");

    public String id() { return ID; }

    public Optional<DiagnosticFinding> evaluate(DiagnosticContext context) {
        if (!context.failing()) return Optional.empty();
        List<LogExcerpt.LogSignal> rejected = PatternSupport.signals(context, LogExcerpt.LogSignal.Kind.AUTH_FAILURE);
        if (rejected.isEmpty()) return Optional.empty();
        String user = rejected.stream().map(signal -> USER.matcher(signal.line())).filter(Matcher::find).map(m -> m.group(1)).findFirst().orElse(null);
        DiagnosticFinding.Builder finding = DiagnosticFinding.builder(id(), "Credentials rejected by a dependency", "credentials")
                .cause("A dependency rejected the application's credentials" + (user == null ? "" : " for user " + user) + ".")
                .explanation("The application logs show authentication failures (" + rejected.size() + " line(s)), so the dependency is reachable and answering "
                        + "but refuses the credential the application presents. Restarting reconnects with the same credential, so no runtime action applies; "
                        + "the credential source has to change (a rotated password, a hard-coded value, or configuration), then the application must be redeployed.")
                .confidence(0.95)
                .action(RecoveryActionType.INVESTIGATE, context.appContainer(),
                        "Correct the credential the application uses (code or configuration) and redeploy it; restarting would reuse the rejected credential.");
        PatternSupport.citeFailures(finding, context);
        rejected.stream().limit(2).forEach(signal -> finding.support(DiagnosticContext.logRef(context.logs(), signal)));
        finding.missing("Where the application reads this credential from (source code, configuration or a secret store)");
        return Optional.of(finding.build());
    }
}
