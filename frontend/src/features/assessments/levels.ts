import { Github, Globe, ScanSearch } from 'lucide-react';
import type { AssessmentKind } from '@/types/domain';

type KindOption = { value: AssessmentKind; label: string; detail: string; icon: typeof Github };
export type InvestigationLevel = 2 | 3;

/** Level 2 and Level 3 are separate experiences on one assessment engine. Level 1 (runtime) lives under Systems. */
export const LEVELS: Record<InvestigationLevel, { tag: string; title: string; path: string; description: string; flow: string[]; kinds: KindOption[] }> = {
  2: {
    tag: 'Level 2 · Codebase Self-Healing', title: 'Codebase', path: '/codebase',
    description: 'Analyze a public GitHub repository, repair what has a deterministic fix in a disposable sandbox, and publish it as a reviewed pull request.',
    flow: ['Repository', 'Findings', 'Diagnosis', 'Repair', 'Sandbox Validation', 'Security Re-scan', 'Human Approval', 'Branch', 'Commit', 'Pull Request', 'Human Merge'],
    kinds: [{ value: 'repository', label: 'Codebase', detail: 'A public GitHub repository, optionally pinned to a commit', icon: Github }],
  },
  3: {
    tag: 'Level 3 · Live URL Investigation', title: 'Live URL Investigation', path: '/live-url',
    description: 'Observe a live endpoint from outside, layer by layer, and report what the evidence shows. Report only: no code is changed.',
    flow: ['URL', 'DNS', 'TCP', 'TLS', 'HTTP', 'Evidence', 'Diagnosis'],
    kinds: [
      { value: 'url', label: 'Live URL', detail: 'Black-box investigation of one endpoint', icon: Globe },
      { value: 'combined', label: 'Live URL + Repo', detail: 'The repository as extra evidence, never as a cause', icon: ScanSearch },
    ],
  },
};

export function levelOf(kind: AssessmentKind): InvestigationLevel {
  return kind === 'repository' ? 2 : 3;
}

/** Where an assessment is shown: its own level's page. */
export function investigationPath(assessment: { id: string; kind: AssessmentKind }): string {
  return `${LEVELS[levelOf(assessment.kind)].path}?assessment=${encodeURIComponent(assessment.id)}`;
}
