export type Provenance = 'live' | 'historical' | 'estimated' | 'unavailable';

export interface ApiResponse<T> {
  data: T;
  provenance: Provenance;
  updatedAt: string;
}

export type StatusKey =
  | 'healthy'
  | 'degraded'
  | 'unreachable'
  | 'resolved'
  | 'warning'
  | 'active_incident'
  | 'critical'
  | 'diagnosing'
  | 'repairing'
  | 'verifying'
  | 'recovery_verified'
  | 'recovery_failed'
  | 'needs_review'
  | 'unknown'
  | 'blocked_by_policy';

export type VerificationStatus = 'passed' | 'failed' | 'timed_out' | 'pending' | 'not_run';
export type IncidentState = 'detected' | 'diagnosing' | 'action_taken' | 'verifying' | 'recovered' | 'unresolved' | 'needs_review' | 'blocked';
export type RepairState = 'suggested' | 'generating_patch' | 'patch_failed' | 'candidate_patch_created' | 'tests_running' | 'tests_passed' | 'tests_failed' | 'deploying_test' | 'verifying' | 'recovery_verified' | 'verification_failed' | 'verification_timed_out' | 'human_review_required' | 'rejected';
export type IntegrationState = 'connected' | 'needs_configuration' | 'needs_verification' | 'not_configured' | 'connection_failed' | 'planned';
export type RecoveryPolicyMode = 'disabled' | 'require_approval' | 'allow_automatically';

export interface Application {
  id: string;
  name: string;
  url: string;
  framework: string;
  environment: 'Development' | 'Staging' | 'Production';
  status: StatusKey;
  responseMs: number | null;
  errorRate: number | null;
  lastChecked: string | null;
  deploymentSha: string;
  githubRepository: string | null;
  dockerContainer: string | null;
  integrations: { url: IntegrationState; docker: IntegrationState; github: IntegrationState };
  description: string;
  provenance: Provenance;
}

/** Level 1 runtime view of a System (GET /api/applications/{id}/runtime). States are observed, never inferred. */
export type RuntimeState = 'healthy' | 'unhealthy' | 'starting' | 'stopped' | 'restarting' | 'missing' | 'detached' | 'not_observable';
export interface RuntimeComponent { role: 'application' | 'dependency'; name: string; container: string | null; source: string; inspectable: boolean; recoverable: boolean; state: RuntimeState; detail: string; restartCount: number | null; startedAt: string | null }
export interface LogSignalSummary { kind: string; count: number; latest: string }
export interface SystemRuntime { applicationId: string; dockerEnabled: boolean; application: RuntimeComponent | null; dependencies: RuntimeComponent[]; logsAvailable: boolean; logsUnavailableReason: string | null; logSignals: LogSignalSummary[]; unavailable: string[]; observedAt: string }

export interface HealthSample {
  id: string;
  applicationId: string;
  checkedAt: string;
  healthy: boolean;
  responseMs: number | null;
  errorMessage: string | null;
  provenance: Provenance;
  /** healthy | degraded (non-2xx response) | unreachable (no response); 'failed' for checks recorded before outcomes existed. */
  outcome: 'healthy' | 'degraded' | 'unreachable' | 'failed';
  statusCode: number | null;
}

export interface EvidenceItem {
  id: string;
  /** 'recovery' records policy decisions and attempts; 'verification' records post-action checks. */
  type: 'health_check' | 'log' | 'metric' | 'container' | 'deployment' | 'recovery' | 'verification';
  title: string;
  detail: string;
  source: string;
  timestamp: string;
  observed: true;
}

export interface RecoveryStep {
  id: string;
  label: string;
  detail: string;
  timestamp: string | null;
  state: 'completed' | 'current' | 'pending' | 'blocked' | 'failed' | 'unresolved';
  actor: 'system' | 'ai' | 'operator';
}

export interface RecommendedAction {
  action: string;
  rationale: string;
  risk: 'low' | 'medium' | 'high' | 'unknown';
}

/**
 * A diagnosis. provider 'deterministic' is the evidence-driven engine (no AI needed or AI not yet run), 'gemini' is
 * AI output layered on the engine result, 'deterministic-fallback' is the engine result after AI was unavailable.
 */
export interface Diagnosis {
  incidentId: string;
  probableCause: string;
  explanation: string;
  confidence: 'Low' | 'Medium' | 'High' | null;
  confidenceReason: string;
  supportingEvidence: string[];
  missingEvidence: string[];
  recommendedActions: RecommendedAction[];
  provider: 'gemini' | 'deterministic' | 'deterministic-fallback' | string;
  model: string | null;
  fallbackReason: string | null;
  validationNotes: string[];
  generatedAt: string;
  provenance: Provenance;
  /** 'automatic' when started by the incident pipeline, 'manual' for an operator re-run; null on older records. */
  trigger: 'automatic' | 'manual' | null;
  /** Always true: a diagnosis never authorizes an action. */
  humanApprovalRequired: boolean;
  approvalReason: string | null;
  /** Why AI output was not used (e.g. 'timeout', 'http_503', 'not_configured'); null when AI answered. */
  aiFailureCode: string | null;
  /** Background AI retries already made, and when the next is due (null = none scheduled). */
  aiRetryAttempts: number;
  nextAiRetryAt: string | null;
  /** Append-only audit of every diagnosis attempt, oldest first (absent on records created before it existed). */
  aiAttempts?: AiAttempt[];
  /** Ranked deterministic hypotheses, kept separately from any AI output (absent on older records). */
  engine?: DeterministicDiagnosis | null;
  /** The deterministic hypothesis (patternId) AI judged most likely; 'none' if it rejected all; null without AI. */
  aiSelectedHypothesis?: string | null;
}

export interface EvidenceRef {
  id: string;
  source: string;
  summary: string;
}

export interface Hypothesis {
  patternId: string;
  title: string;
  category: string;
  cause: string;
  explanation: string;
  /** 0..1 */
  confidence: number;
  confidenceLabel: 'Low' | 'Medium' | 'High' | string;
  supportingEvidence: EvidenceRef[];
  missingEvidence: string[];
  contradictoryEvidence: EvidenceRef[];
  recommendedAction: string;
  actionTarget: string | null;
  actionText: string;
}

export interface DeterministicDiagnosis {
  topPatternId: string | null;
  topConfidence: number | null;
  /** 'required' when deterministic confidence was insufficient and AI reasoning was requested. */
  aiEscalation: 'required' | 'not_required' | string;
  escalationReason: string | null;
  hypotheses: Hypothesis[];
  evidenceSources: string[];
  unavailableEvidence: string[];
  generatedAt: string;
}

/** Consecutive healthy checks since detection versus the configured threshold that closes the incident. */
export interface ResolutionProgress {
  incidentId: string;
  open: boolean;
  consecutiveHealthyChecks: number;
  requiredHealthyChecks: number;
  latestOutcome: 'healthy' | 'degraded' | 'unreachable' | 'failed' | null;
  latestCheckAt: string | null;
}

export interface AiAttempt {
  at: string;
  trigger: 'automatic' | 'manual' | string;
  /** 0 for the first or a manual attempt; n for the n-th background AI retry. */
  backgroundRetry: number;
  outcome: 'ai' | 'rule-based' | 'deterministic';
  aiFailureCode: string | null;
  reason: string | null;
  probableCause: string;
}

export interface Incident {
  id: string;
  title: string;
  applicationId: string;
  applicationName: string;
  severity: 'Sev-1' | 'Sev-2' | 'Sev-3';
  state: IncidentState;
  status: StatusKey;
  detectedAt: string;
  detectionDurationMs?: number | null;
  detectedLabel: string;
  component: string;
  summary: string;
  likelyCause: string;
  confidence: 'Low' | 'Medium' | 'High' | null;
  supportingEvidence: string[];
  evidence: EvidenceItem[];
  verification: { status: VerificationStatus; detail: string };
  recoveryDurationMs: number | null;
  timeline: RecoveryStep[];
  provenance: Provenance;
  /** Latest stored diagnosis; null until one has run. */
  diagnosis: Diagnosis | null;
}

export interface RecoveryEvent {
  id: string;
  incidentId: string;
  applicationName: string;
  event: string;
  outcome: StatusKey;
  timestamp: string;
  detectionDurationMs: number | null;
  recoveryDurationMs: number | null;
  detail: string;
  provenance: Provenance;
}

export interface RecoveryPolicy {
  id: string;
  action: string;
  description: string;
  mode: RecoveryPolicyMode;
  risk: 'low' | 'medium' | 'high';
  scope: string;
  provenance: Provenance;
}

export interface Integration {
  id: 'url' | 'docker' | 'github' | 'gemini';
  name: string;
  description: string;
  state: IntegrationState;
  capabilities: string[];
  lastVerified: string | null;
  provenance: Provenance;
}

export interface RepairJob {
  id: string;
  /** Null for patch proposals that originate from an assessment finding. */
  incidentId: string | null;
  applicationName: string;
  repository: string;
  commitSha: string;
  state: RepairState;
  explanation: string;
  filesChanged: number;
  diff: { file: string; additions: string[]; removals: string[] }[];
  checks: { name: string; status: 'passed' | 'failed' | 'running' | 'not_run' | 'not_available'; detail: string }[];
  verification: { status: VerificationStatus; detail: string };
  provenance: Provenance;
  assessmentId: string | null;
  findingId: string | null;
  findingTitle: string | null;
  filePath: string | null;
  strategy: string | null;
  /** Unified diff for review; credential values are redacted, so it may not apply verbatim. */
  patch: string | null;
  createdAt: string | null;
  updatedAt: string;
}

export interface DashboardSummary {
  connectedApplications: number;
  healthyServices: number;
  activeIncidents: number;
  verifiedRecoveries30d: number;
  responseTimeSeries: { time: string; p95: number; errorRate: number }[];
  provenance: Provenance;
}

export type AssessmentKind = 'repository' | 'url' | 'combined';
export type AssessmentRunStatus = 'queued' | 'running' | 'completed' | 'failed';
export type AssessmentSeverity = 'info' | 'low' | 'medium' | 'high' | 'critical';

export interface AssessmentFinding {
  id: string;
  category: string;
  title: string;
  severity: AssessmentSeverity | string;
  confidence: 'low' | 'medium' | 'high' | string;
  file: string | null;
  line: number | null;
  evidence: string;
  explanation: string;
  recommendation: string;
  validationStatus: string;
}

export interface AssessmentCheck {
  name: string;
  status: 'passed' | 'warning' | 'failed' | 'not_run' | string;
  detail: string;
}

export interface AssessmentTimelineStep {
  id: string;
  stage: string;
  label: string;
  detail: string;
  state: 'current' | 'completed' | 'failed' | 'skipped' | string;
  timestamp: string | null;
}

export interface Assessment {
  id: string;
  kind: AssessmentKind;
  repositoryUrl: string | null;
  applicationUrl: string | null;
  status: AssessmentRunStatus;
  currentStage: string;
  timeline: AssessmentTimelineStep[];
  summary: string;
  repositoryCommit: string | null;
  aiTriageStatus: string;
  aiSummary: string | null;
  findings: AssessmentFinding[];
  checks: AssessmentCheck[];
  errorMessage: string | null;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  provenance: Provenance;
  /** Findings for which the backend can generate a deterministic patch proposal. */
  patchableFindingIds: string[];
  /** Canonical diagnosis; present on the detail endpoint only. */
  diagnosis?: AssessmentDiagnosis | null;
}

export interface CreateAssessmentRequest {
  kind: AssessmentKind;
  repositoryUrl?: string;
  applicationUrl?: string;
  authorized: boolean;
  aiTriage: boolean;
}

export interface AssessmentReport {
  assessmentId: string;
  markdown: string;
  emailSubject: string;
  emailBody: string;
}

export type GitHubRemediationStatus = 'APPLYING' | 'BRANCH_CREATED' | 'COMMITTED' | 'PR_CREATED' | 'FAILED';

/** One audited attempt to open a GitHub pull request for a patch proposal. Never contains credentials. */
export interface GitHubRemediation {
  id: string;
  patchId: string;
  status: GitHubRemediationStatus;
  repository: string;
  baseBranch: string | null;
  branch: string | null;
  commitSha: string | null;
  prNumber: number | null;
  prUrl: string | null;
  validationStatus: string;
  preexistingFailuresAcknowledged: boolean;
  failedStage: string | null;
  errorCode: string | null;
  errorMessage: string | null;
  verificationStatus: string | null;
  verificationDetail: string | null;
  createdAt: string;
  updatedAt: string;
}

/** Whether the signed-in user may apply a patch to GitHub, what would happen, and the latest attempt. */
/** Read from GitHub on each view; RELAY never merges. NONE = no pull request yet. */
export interface MergeStatus { state: 'NONE' | 'OPEN' | 'CLOSED' | 'MERGED' | 'UNKNOWN'; verifiedOnDefaultBranch: boolean; detail: string }

export interface GitHubRemediationView {
  patchId: string;
  available: boolean;
  blockedReasons: string[];
  configured: boolean;
  authorized: boolean;
  /** 'passed' | 'preexisting_test_failures' | null (not applicable). */
  validation: string | null;
  requiresAcknowledgement: boolean;
  repository: string | null;
  findingId: string | null;
  findingTitle: string | null;
  plannedBranch: string | null;
  targetBranch: string | null;
  action: string;
  latest: GitHubRemediation | null;
  merge?: MergeStatus | null;
}

/** Certainty of one statement. AI output is always a HYPOTHESIS. */
export type Certainty = 'OBSERVED' | 'INFERRED' | 'HYPOTHESIS' | 'UNKNOWN';
export type EvidenceSufficiency = 'SUFFICIENT' | 'PARTIAL' | 'INSUFFICIENT';
export type Actionability = 'AUTO_REPAIRABLE' | 'ACTION_REQUIRED' | 'INFORMATIONAL';
/** Unified repair state shared by finding cards and Auto-Repair progress. RESOLVED only after verification passed. */
export type AutoRepairState = 'REPAIRABLE' | 'REPAIRING' | 'VALIDATION_FAILED' | 'BLOCKED' | 'AWAITING_APPROVAL' | 'REPAIR_ATTEMPTED' | 'VERIFICATION_FAILED' | 'RESOLVED' | 'MANUAL_ACTION_REQUIRED';
export type RepairActionState = AutoRepairState | 'NONE';

export interface RepairStep { name: string; status: 'passed' | 'failed' | 'running' | 'pending' | 'skipped' | string; detail: string | null }
export interface RepairProgress { strategy: string; type: 'GITHUB_PATCH' | 'RUNTIME_RECOVERY' | string; state: AutoRepairState; headline: string; detail: string; steps: RepairStep[]; targetModified: string | null; detailsLink: string | null }

export interface EvidenceStatement { certainty: Certainty; text: string; source: string }

/** How an existing engine can act on a finding: GITHUB_PATCH, RUNTIME_RECOVERY or NONE. */
export interface RepairAction { type: 'GITHUB_PATCH' | 'RUNTIME_RECOVERY' | 'NONE'; label: string; targetId: string | null; state: RepairActionState; stateDetail: string | null; link: string | null }

export interface CanonicalFinding {
  id: string;
  source: 'LIVE_URL' | 'GITHUB' | 'RUNTIME' | 'INCIDENT' | 'CORRELATED' | string;
  title: string;
  description: string;
  severity: string;
  confidence: string;
  component: string;
  statements: EvidenceStatement[];
  evidenceSufficiency: EvidenceSufficiency;
  sufficiencyReason: string;
  impact: string;
  actionability: Actionability;
  actionabilityReason: string;
  repair: RepairAction;
  verificationState: string;
  provenance: string;
  relatedEvidence: string[];
  nextBestEvidence: string[];
  rank: number;
  rankReason: string;
}

export interface LayerStatus { layer: string; status: 'PASSED' | 'FAILED' | 'NOT_TESTED' | 'UNKNOWN'; detail: string }

export interface AssessmentDiagnosis {
  overallStatus: 'FAILING' | 'DEGRADED' | 'ACTION_REQUIRED' | 'ATTENTION' | 'HEALTHY' | 'INCOMPLETE' | string;
  whatHappened: string;
  primary: CanonicalFinding | null;
  layers: LayerStatus[];
  samplingPattern: string;
  samplingDetail: string;
  findings: CanonicalFinding[];
  correlations: CanonicalFinding[];
  independentEvidence: string[];
  sources: string[];
  aiHypothesis: string | null;
}
