import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter, Route, Routes } from 'react-router-dom';
import { AppShell } from '@/components/layout/AppShell';
import { OverviewPage } from '@/features/overview/OverviewPage';
import { ApplicationsPage } from '@/features/applications/ApplicationsPage';
import { ApplicationDetailPage } from '@/features/applications/ApplicationDetailPage';
import { IncidentsPage } from '@/features/incidents/IncidentsPage';
import { IncidentDetailPage } from '@/features/incidents/IncidentDetailPage';
import { AssessmentsPage, InvestigationRedirect } from '@/features/assessments/AssessmentsPage';
import { RepairsPage } from '@/features/repairs/RepairsPage';
import { RecoveryHistoryPage } from '@/features/history/RecoveryHistoryPage';
import { IntegrationsPage } from '@/features/integrations/IntegrationsPage';
import { SettingsPage } from '@/features/settings/SettingsPage';
import { EmptyState } from '@/components/feedback/States';
import { AuthProvider } from '@/features/auth/AuthProvider';
import { ProtectedRoute, PublicOnlyRoute } from '@/features/auth/RouteGuards';
import { LoginPage } from '@/features/auth/LoginPage';
import { RegisterPage } from '@/features/auth/RegisterPage';

const queryClient = new QueryClient({ defaultOptions: { queries: { retry: 1, staleTime: 1000 }, mutations: { retry: 0 } } });

function NotFoundPage() {
  return <EmptyState title="Page not found" detail="This page is not part of the current RELAY AEGIS application." action={<a className="btn btn-primary" href="/">Return to overview</a>} />;
}

export default function App() {
  return <QueryClientProvider client={queryClient}><BrowserRouter><AuthProvider><Routes>
    <Route element={<PublicOnlyRoute />}>
      <Route path="login" element={<LoginPage />} />
      <Route path="register" element={<RegisterPage />} />
    </Route>
    <Route element={<ProtectedRoute />}><Route element={<AppShell />}>
      <Route index element={<OverviewPage />} />
      <Route path="applications" element={<ApplicationsPage />} />
      <Route path="applications/:id" element={<ApplicationDetailPage />} />
      <Route path="incidents" element={<IncidentsPage />} />
      <Route path="incidents/:id" element={<IncidentDetailPage />} />
      <Route path="codebase" element={<AssessmentsPage key="level-2" level={2} />} />
      <Route path="live-url" element={<AssessmentsPage key="level-3" level={3} />} />
      <Route path="assessments" element={<InvestigationRedirect />} />
      <Route path="repairs" element={<RepairsPage />} />
      <Route path="repairs/:id" element={<RepairsPage />} />
      <Route path="history" element={<RecoveryHistoryPage />} />
      <Route path="integrations" element={<IntegrationsPage />} />
      <Route path="settings" element={<SettingsPage />} />
      <Route path="*" element={<NotFoundPage />} />
    </Route></Route>
  </Routes></AuthProvider></BrowserRouter></QueryClientProvider>;
}
