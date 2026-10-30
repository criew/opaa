import { Suspense, useEffect, useMemo } from 'react'
import { CssBaseline, ThemeProvider, useMediaQuery } from '@mui/material'
import { BrowserRouter, HashRouter, Navigate, Route, Routes, useParams } from 'react-router'
// app
import ErrorBoundary from './ErrorBoundary'
import ProtectedRoute from './ProtectedRoute'
import InstallationRedirect from './InstallationRedirect'
import { createAppTheme } from '@/shared/theme/theme'
import { resolveThemeMode } from '@/shared/theme/colorScheme'
import GlobalAreaLayout from './shell/GlobalAreaLayout'
import AppShell from './shell/AppShell'
import PageLoading from './shell/PageLoading'
import { useFeatureEnabled } from '@/shared/session/useFeatureEnabled'
import { useUiStore } from '@/shared/stores/uiStore'
import { renderSpaceChats } from './themeSeams'
// shared
import {
  CATALOG_ROUTE,
  CONNECTION_CALLBACK_ROUTE,
  AUDITOR_ROLE_REQUESTS_ROUTE,
  CONNECTION_LOG_ROUTE,
  CONNECTION_PROFILES_ROUTE,
  CONNECTIONS_ADMIN_ROUTE,
  EXTERNAL_TOOLS_LIBRARIES_ROUTE,
  EXTERNAL_TOOLS_ROUTE,
  EXTERNAL_TOOLS_SETTINGS_ROUTE,
  EXTERNAL_TOOLS_TOKENS_ROUTE,
  FORGOT_PASSWORD_ROUTE,
  HANDOVER_ROUTE,
  INSTALLATION_ROUTE,
  REGISTER_ROUTE,
  RIGHTS_HISTORY_ROUTE,
  SET_PASSWORD_ROUTE,
  spaceSettingsRoute,
  VERIFY_EMAIL_ROUTE,
} from '@/shared/routes'
import { useAuthStore } from '@/shared/session/authStore'
import { catalogRoute } from '@/shared/assetTypes'
import { useBrandingStore } from '@/shared/branding/brandingStore'
// pages, loaded on demand
import {
  AuditorRoleRequestsPage,
  AuthCallbackPage,
  BrandingSettingsPage,
  CapabilityManagementPage,
  CatalogNewPage,
  CatalogPage,
  ChangePasswordPage,
  ChatPage,
  ChatRedirect,
  ChatsPage,
  ConnectionCallbackPage,
  ConnectionLogPage,
  ConnectionsAdminPage,
  DirectorySyncPage,
  ForgotPasswordPage,
  GroupManagementPage,
  HandoverPage,
  InstallationPage,
  LibraryCreatePage,
  LibraryDetailPage,
  LlmModelManagementPage,
  LoginPage,
  MailSettingsPage,
  OidcProviderManagementPage,
  PrivacySettingsPage,
  PromptLibraryCreatePage,
  PromptLibraryDetailPage,
  ProviderGroupWorklistPage,
  RegisterPage,
  RightsHistoryPage,
  SearchIndexingAdminPage,
  SetPasswordPage,
  SettingsPage,
  SettingsTransferPage,
  SpaceCreatePage,
  SpacePage,
  SpaceSettingsPage,
  SpacesOverviewPage,
  SuccessionPage,
  SystemLoginPage,
  UserManagementPage,
  VerifyEmailPage,
} from './lazyPages'

const ADMIN_SECTIONS = [
  { label: 'Allgemein & Branding', to: '/admin/branding' },
  { label: 'Einstellungen übertragen', to: '/admin/settings-transfer' },
  // „Benutzer & Gruppen" as two entries instead of tabs on one page: the
  // secondary column *is* the area navigation, and a pair of tabs above it would be a second
  // navigation for the same decision. Every target thus stays directly linkable.
  { label: 'Benutzer', to: '/admin/users' },
  { label: 'Gruppen', to: '/admin/groups' },
  { label: 'Anlegerechte', to: '/admin/capabilities' },
  { label: 'Ohne Zuständigkeit', to: '/admin/succession' },
  { label: 'Modelle', to: '/admin/models' },
  { label: 'Identitätsanbieter', to: '/admin/identity-providers' },
  { label: 'Verzeichnisabgleich', to: '/admin/directory-sync' },
  { label: 'E-Mail', to: '/admin/mail' },
  // Connection profiles, source types and external AI tools as one area with tabs.
  { label: 'Anbindungen', to: CONNECTIONS_ADMIN_ROUTE },
  { label: 'Suche & Indexierung', to: '/admin/search' },
  { label: 'Datenschutz', to: '/admin/privacy' },
]

/**
 * A regular user deep-linking into /admin/* gets the bare frame: showing them the full
 * administration navigation with "nicht freigegeben" destinations helps no one.
 * The pages keep their own role gates.
 */
function AdminAreaLayout() {
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')
  return isSystemAdmin ? (
    <GlobalAreaLayout title="Administration" sections={ADMIN_SECTIONS} />
  ) : (
    <GlobalAreaLayout />
  )
}

const REVISION_SECTIONS = [
  { label: 'Stichtagsauskunft', to: RIGHTS_HISTORY_ROUTE },
  { label: 'Verbindungsprotokoll', to: CONNECTION_LOG_ROUTE },
  { label: 'Rollenanträge', to: AUDITOR_ROLE_REQUESTS_ROUTE },
]

/** The audit's pages share a section column; other roles get the bare frame and the page's gate. */
function RevisionAreaLayout() {
  const isAuditor = useAuthStore((s) => s.user?.systemRole === 'AUDITOR')
  return isAuditor ? (
    <GlobalAreaLayout title="Revision" sections={REVISION_SECTIONS} />
  ) : (
    <GlobalAreaLayout />
  )
}

/** Redirects to the first tab of the space settings - without loading the space. */
function SpaceSettingsRedirect() {
  const { spaceId } = useParams<{ spaceId: string }>()
  return <Navigate to={spaceId ? spaceSettingsRoute(spaceId) : '/spaces'} replace />
}

/**
 * The static UI-lab prototype is served as plain files from any path, without a server that
 * answers every route with index.html; the route therefore lives in the hash there.
 */
const Router = import.meta.env.MODE === 'prototype' ? HashRouter : BrowserRouter

export default function App() {
  const initialize = useAuthStore((s) => s.initialize)
  const promptLibraries = useFeatureEnabled('promptLibraries')
  const externalAccess = useFeatureEnabled('externalAccess')
  const themeMode = useUiStore((s) => s.themeMode)
  const branding = useBrandingStore((s) => s.branding)
  const loadBranding = useBrandingStore((s) => s.loadBranding)
  const prefersDark = useMediaQuery('(prefers-color-scheme: dark)')

  // The operator's default applies only where the user has made no choice of their own - see
  // resolveThemeMode. `system` then still follows the browser, as it always has.
  const preferredMode = resolveThemeMode(themeMode, branding.defaultColorScheme)
  const effectiveMode =
    preferredMode === 'system' ? (prefersDark ? 'dark' : 'light') : preferredMode
  // The accent is the only branding value the theme itself consumes; product name, claim and logo
  // are read from the store by the components that render them (guidelines 7).
  const theme = useMemo(
    () => createAppTheme(effectiveMode, { primaryColor: branding.primaryColor }),
    [effectiveMode, branding.primaryColor],
  )

  useEffect(() => {
    initialize()
    // Deliberately not awaited and deliberately outside any auth gate: the sign-in page needs the
    // branding too, and the store falls back to the OPAA standard if the request fails.
    void loadBranding()
  }, [initialize, loadBranding])

  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <ErrorBoundary>
        <Router>
          <InstallationRedirect />
          <Suspense fallback={<PageLoading />}>
            <Routes>
              {/* ADR-0060: the set-up assistant, outside the shell and before any session. */}
              <Route path={INSTALLATION_ROUTE} element={<InstallationPage />} />
              <Route path="/login" element={<LoginPage />} />
              {/* ADR-0033, Entscheidung 4/5: reachable at all times - the way back into an
                installation whose last identity provider is misconfigured. */}
              <Route path="/login/system" element={<SystemLoginPage />} />
              {/* Outside the application shell on purpose: while a password change is owed, every
                route but /api/v1/auth/local/* answers 403 (ADR-0033, Entscheidung 8). */}
              <Route path="/account/password" element={<ChangePasswordPage />} />
              {/* The self-service pages of local accounts, public like the sign-in page.
                ADR-0033, Entscheidung 11: the two link targets work whatever the switches say -
                a link from an invitation or an administrative reset must never run into a
                redirect - while /register and /forgot-password are pages only where the
                installation offers the flow (the pages redirect to /login otherwise). */}
              <Route path={SET_PASSWORD_ROUTE} element={<SetPasswordPage />} />
              <Route path={VERIFY_EMAIL_ROUTE} element={<VerifyEmailPage />} />
              <Route path={FORGOT_PASSWORD_ROUTE} element={<ForgotPasswordPage />} />
              <Route path={REGISTER_ROUTE} element={<RegisterPage />} />
              {/* ADR-0033, Entscheidung 12: the handover link and, after the provider redirect, the
                page that redeems it - public like the other link targets, because the person has
                no OPAA session at that moment and must not get one before the redemption. */}
              <Route path={HANDOVER_ROUTE} element={<HandoverPage />} />
              <Route path="/auth/callback" element={<AuthCallbackPage />} />
              {/* ADR-0025: the provider of an OAuth consent returns here. Outside the protected
                routes, so code and state never ride along into a sign-in redirect. */}
              <Route path={CONNECTION_CALLBACK_ROUTE} element={<ConnectionCallbackPage />} />
              <Route
                element={
                  <ProtectedRoute>
                    <AppShell />
                  </ProtectedRoute>
                }
              >
                <Route index element={<Navigate to="/chat" replace />} />
                <Route path="chat" element={<ChatRedirect />} />

                <Route path="spaces/:spaceId/chats" element={<ChatsPage />} />
                <Route path="spaces/:spaceId/chats/:chatId" element={<ChatPage />} />
                <Route
                  path="spaces/:spaceId"
                  element={<SpacePage renderChats={renderSpaceChats} />}
                />
                {/* The former administration page: bookmarks to /manage land on the first
                   tab of the settings. */}
                <Route path="spaces/:spaceId/manage" element={<SpaceSettingsRedirect />} />
                <Route path="spaces/:spaceId/settings" element={<SpaceSettingsRedirect />} />
                <Route path="spaces/:spaceId/settings/:tab" element={<SpaceSettingsPage />} />
                {/* Guidelines 5.13: overview (the space search) and create wizard open in the
                  content area; the space column stays beside them, marking the space last
                  active. */}
                <Route path="spaces" element={<SpacesOverviewPage />} />
                <Route path="spaces/new" element={<SpaceCreatePage />} />
                {/* Every asset type's pages are a global area too (Schlussnotiz von
                  Mockup-Abschnitt 2): bare global frame, no secondary column. The catalog is
                  their one overview (ADR-0039); the former type overviews lead into it. */}
                <Route element={<GlobalAreaLayout />}>
                  <Route path="catalog" element={<CatalogPage />} />
                  <Route path="catalog/new" element={<CatalogNewPage />} />
                  <Route
                    path="libraries"
                    element={<Navigate to={catalogRoute('KNOWLEDGE_LIBRARY')} replace />}
                  />
                  <Route path="libraries/new" element={<LibraryCreatePage />} />
                  <Route path="libraries/:libraryId" element={<LibraryDetailPage />} />
                  {/* Switched off (ADR-0044), every former prompt address leads to the catalog. */}
                  {promptLibraries ? (
                    <>
                      <Route
                        path="prompts"
                        element={<Navigate to={catalogRoute('PROMPT_LIBRARY')} replace />}
                      />
                      <Route path="prompts/new" element={<PromptLibraryCreatePage />} />
                      <Route
                        path="prompts/:promptLibraryId"
                        element={<PromptLibraryDetailPage />}
                      />
                      <Route
                        path="prompts/:promptLibraryId/:tab"
                        element={<PromptLibraryDetailPage />}
                      />
                    </>
                  ) : (
                    <Route path="prompts/*" element={<Navigate to={CATALOG_ROUTE} replace />} />
                  )}
                </Route>
                {/* Global areas render inside the frame from mockup 2b: no space
                  column, a light secondary column with the area navigation instead. */}
                <Route element={<AdminAreaLayout />}>
                  {/* The two areas of the user page are routes, as on the mail page:
                     the jump from the requirements hint lands in the account list, the one from
                     the login page in the settings, and a reload keeps the area. */}
                  <Route
                    path="admin/users"
                    element={<Navigate to="/admin/users/accounts" replace />}
                  />
                  <Route path="admin/users/:tab" element={<UserManagementPage />} />
                  <Route path="admin/groups" element={<GroupManagementPage />} />
                  <Route path="admin/branding" element={<BrandingSettingsPage />} />
                  <Route path="admin/settings-transfer" element={<SettingsTransferPage />} />
                  <Route
                    path="admin/models"
                    element={<Navigate to="/admin/models/chat" replace />}
                  />
                  <Route path="admin/models/:tab" element={<LlmModelManagementPage />} />
                  <Route path="admin/identity-providers" element={<OidcProviderManagementPage />} />
                  <Route
                    path="admin/identity-providers/:providerId/groups"
                    element={<ProviderGroupWorklistPage />}
                  />
                  <Route path="admin/directory-sync" element={<DirectorySyncPage />} />
                  <Route
                    path="admin/connections"
                    element={<Navigate to={CONNECTION_PROFILES_ROUTE} replace />}
                  />
                  <Route path="admin/connections/:tab" element={<ConnectionsAdminPage />} />
                  {/* The former pages of the connections area: bookmarks and links land
                     on the same content in the new area. */}
                  <Route
                    path="admin/connection-profiles"
                    element={<Navigate to={CONNECTION_PROFILES_ROUTE} replace />}
                  />
                  {/* Switched off (ADR-0044), the former addresses of the external tools lead to
                    the first tab of the area. */}
                  {externalAccess ? (
                    <>
                      <Route
                        path="admin/library-releases"
                        element={<Navigate to={EXTERNAL_TOOLS_LIBRARIES_ROUTE} replace />}
                      />
                      <Route
                        path="admin/external-access"
                        element={<Navigate to={EXTERNAL_TOOLS_SETTINGS_ROUTE} replace />}
                      />
                      <Route
                        path="admin/external-access/channel"
                        element={<Navigate to={EXTERNAL_TOOLS_SETTINGS_ROUTE} replace />}
                      />
                      <Route
                        path="admin/external-access/tokens"
                        element={<Navigate to={EXTERNAL_TOOLS_TOKENS_ROUTE} replace />}
                      />
                      <Route
                        path="admin/external-access/*"
                        element={<Navigate to={EXTERNAL_TOOLS_ROUTE} replace />}
                      />
                    </>
                  ) : (
                    <>
                      <Route
                        path="admin/library-releases"
                        element={<Navigate to={CONNECTION_PROFILES_ROUTE} replace />}
                      />
                      <Route
                        path="admin/external-access/*"
                        element={<Navigate to={CONNECTION_PROFILES_ROUTE} replace />}
                      />
                    </>
                  )}
                  <Route path="admin/capabilities" element={<CapabilityManagementPage />} />
                  <Route
                    path="admin/succession"
                    element={<Navigate to="/admin/succession/open" replace />}
                  />
                  <Route path="admin/succession/:tab" element={<SuccessionPage />} />
                  {/* The two areas of the mail page are routes, so that a link to the
                     template management lands there. */}
                  <Route path="admin/mail" element={<Navigate to="/admin/mail/server" replace />} />
                  <Route path="admin/mail/:tab" element={<MailSettingsPage />} />
                  <Route
                    path="admin/search"
                    element={<Navigate to="/admin/search/overview" replace />}
                  />
                  <Route path="admin/search/:tab" element={<SearchIndexingAdminPage />} />
                  <Route path="admin/privacy" element={<PrivacySettingsPage />} />
                </Route>
                {/* Mockup 2c: the user settings render in the bare global frame -
                  no space column, no secondary column. */}
                {/* The audit's point-in-time report: its own area, not under
                   /admin - it belongs to the AUDITOR role, not to the system administration. */}
                <Route element={<RevisionAreaLayout />}>
                  <Route path="revision/rechtehistorie" element={<RightsHistoryPage />} />
                  <Route path="revision/verbindungsprotokoll" element={<ConnectionLogPage />} />
                  <Route path="revision/rollenantraege" element={<AuditorRoleRequestsPage />} />
                </Route>
                <Route element={<GlobalAreaLayout />}>
                  <Route path="settings" element={<SettingsPage />} />
                  <Route path="settings/:tab" element={<SettingsPage />} />
                </Route>
                <Route path="*" element={<Navigate to="/chat" replace />} />
              </Route>
            </Routes>
          </Suspense>
        </Router>
      </ErrorBoundary>
    </ThemeProvider>
  )
}
