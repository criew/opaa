import { lazy, type ComponentType, type LazyExoticComponent } from 'react'

type PropsOf<C> = C extends ComponentType<infer P> ? P : never

/**
 * Pages are loaded on demand, one chunk per theme: every page of a theme comes from the same
 * dynamic import of its `routes.ts`, so the first page of a theme loads all of them at once.
 */
function themePages<M>(load: () => Promise<M>) {
  return <K extends keyof M>(name: K): LazyExoticComponent<ComponentType<PropsOf<M[K]>>> =>
    lazy(async () => ({
      default: (await loadOrWait(load))[name] as ComponentType<PropsOf<M[K]>>,
    }))
}

/**
 * Resolves with the module. A failed load answered with a reload (see preloadErrorReload)
 * resolves without one; the promise then stays pending, so the fallback shows until the reload.
 */
export async function loadOrWait<T>(load: () => Promise<T>): Promise<T> {
  const module = await load()
  return module ?? new Promise<T>(() => {})
}

const auth = themePages(() => import('@/features/auth/routes'))
export const AuthCallbackPage = auth('AuthCallbackPage')
export const ChangePasswordPage = auth('ChangePasswordPage')
export const ForgotPasswordPage = auth('ForgotPasswordPage')
export const HandoverPage = auth('HandoverPage')
export const LoginPage = auth('LoginPage')
export const RegisterPage = auth('RegisterPage')
export const SetPasswordPage = auth('SetPasswordPage')
export const SystemLoginPage = auth('SystemLoginPage')
export const VerifyEmailPage = auth('VerifyEmailPage')

const chat = themePages(() => import('@/features/chat/routes'))
export const ChatPage = chat('ChatPage')
export const ChatRedirect = chat('ChatRedirect')
export const ChatsPage = chat('ChatsPage')

const space = themePages(() => import('@/features/space/routes'))
export const SpaceCreatePage = space('SpaceCreatePage')
export const SpacePage = space('SpacePage')
export const SpaceSettingsPage = space('SpaceSettingsPage')
export const SpacesOverviewPage = space('SpacesOverviewPage')

const asset = themePages(() => import('@/features/asset/routes'))
export const CatalogNewPage = asset('CatalogNewPage')
export const CatalogPage = asset('CatalogPage')

const library = themePages(() => import('@/features/library/routes'))
export const ConnectionCallbackPage = library('ConnectionCallbackPage')
export const LibraryCreatePage = library('LibraryCreatePage')
export const LibraryDetailPage = library('LibraryDetailPage')

const prompt = themePages(() => import('@/features/prompt/routes'))
export const PromptLibraryCreatePage = prompt('PromptLibraryCreatePage')
export const PromptLibraryDetailPage = prompt('PromptLibraryDetailPage')

const group = themePages(() => import('@/features/group/routes'))
export const GroupManagementPage = group('GroupManagementPage')
export const ProviderGroupWorklistPage = group('ProviderGroupWorklistPage')

export const SuccessionPage = themePages(() => import('@/features/succession/routes'))(
  'SuccessionPage',
)
export const CapabilityManagementPage = themePages(() => import('@/features/capability/routes'))(
  'CapabilityManagementPage',
)
export const UserManagementPage = themePages(() => import('@/features/user/routes'))(
  'UserManagementPage',
)
export const OidcProviderManagementPage = themePages(
  () => import('@/features/identity-provider/routes'),
)('OidcProviderManagementPage')
export const DirectorySyncPage = themePages(() => import('@/features/directory-sync/routes'))(
  'DirectorySyncPage',
)
export const LlmModelManagementPage = themePages(() => import('@/features/model/routes'))(
  'LlmModelManagementPage',
)
export const MailSettingsPage = themePages(() => import('@/features/mail/routes'))(
  'MailSettingsPage',
)
export const BrandingSettingsPage = themePages(() => import('@/features/branding/routes'))(
  'BrandingSettingsPage',
)
export const SearchIndexingAdminPage = themePages(() => import('@/features/search-admin/routes'))(
  'SearchIndexingAdminPage',
)

const audit = themePages(() => import('@/features/audit/routes'))
export const ConnectionLogPage = audit('ConnectionLogPage')
export const RightsHistoryPage = audit('RightsHistoryPage')

export const PrivacySettingsPage = themePages(() => import('@/features/compliance/routes'))(
  'PrivacySettingsPage',
)

export const SettingsTransferPage = themePages(() => import('@/features/settings/routes'))(
  'SettingsTransferPage',
)

export const SettingsPage = lazy(() => loadOrWait(() => import('./areas/SettingsPage')))
export const ConnectionsAdminPage = lazy(() =>
  loadOrWait(() => import('./areas/ConnectionsAdminPage')),
)
