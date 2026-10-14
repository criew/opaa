import '@testing-library/jest-dom/vitest'
// Must run before any request is made: it closes the jsdom gap that makes a `FormData` upload
// carrying a `File` hang forever instead of completing (#1169).
import './jsdom-blob-stream'
import { beforeAll, afterEach, afterAll } from 'vitest'
import { server } from './server'
// Deliberately only the fixture module, never a store or `src/shared/api/api` (#583): importing
// anything that pulls in the axios instance *from this file* makes a dozen unrelated dialog tests
// fail - their requests stop being intercepted, so every "expected onCreated to be called" assertion
// times out. Verified by adding a bare `import '@/shared/api/api'` here and watching
// SpaceCreatePage.test.tsx go red on its own. Store state that has to be reset between tests
// belongs in that test file's own beforeEach.

// chat
import { resetChatMockState } from '@/features/chat/mocks/chatHandlers'
// asset
import { resetGrantMockState } from '@/features/asset/mocks/assetHandlers'
import { resetMockSpaceAssetAssociations } from '@/features/asset/mocks/assetFixtures'
// library
import { resetIndexingState } from '@/features/library/mocks/indexingHandlers'
import { resetDocumentMockState } from '@/features/library/mocks/libraryDocumentHandlers'
// prompt
import { resetMockPromptLibraries } from '@/features/prompt/mocks/promptLibraryFixtures'
// user
import {
  resetMockLocalAuthSettings,
  resetMockLocalUsers,
} from '@/features/user/mocks/localUserFixtures'
// identity-provider
import { resetMockOidcProviders } from '@/features/identity-provider/mocks/identityProviderFixtures'
// model
import { resetLlmModelMockState } from '@/features/model/mocks/modelHandlers'
// mail
import { resetMockMailSettings, resetMockMailTemplates } from '@/features/mail/mocks/mailFixtures'
// branding
import { resetMockBranding } from '@/features/branding/mocks/brandingFixtures'
// connection
import { resetConnectionProfileMockState } from '@/features/connection/mocks/connectionProfileHandlers'
import { resetConnectedAccountMockState } from '@/features/connection/mocks/connectedAccountHandlers'
import { resetConnectionLogMockState } from '@/features/audit/mocks/connectionLogHandlers'
// external-access
import { resetMockExternalAccessSettings } from '@/features/external-access/mocks/externalAccessHandlers'
import { resetMockExternalAccessTokens } from '@/features/external-access/mocks/externalAccessTokenHandlers'
// auth
import { resetMockAuthConfig } from '@/shared/mocks/authFixtures'
import { resetMockSelfServiceTokens } from '@/features/auth/mocks/localAuthFixtures'

/**
 * MSW passes a handler's `Set-Cookie` into `document.cookie`, which jsdom keeps for the whole file.
 * A cookie left behind by one test is state the next one never asked for.
 */
function clearCookies() {
  for (const entry of document.cookie.split(';')) {
    const name = entry.split('=')[0]?.trim()
    if (name) document.cookie = `${name}=; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT`
  }
}

beforeAll(() => server.listen())
afterEach(() => {
  server.resetHandlers()
  resetIndexingState()
  resetDocumentMockState()
  resetGrantMockState()
  resetChatMockState()
  resetLlmModelMockState()
  resetConnectionProfileMockState()
  resetConnectedAccountMockState()
  resetConnectionLogMockState()
  // The branding fixture is mutable so a PUT is visible on the next GET (#583) - without this,
  // a test that configures a brand colour would silently set the stage for the next one.
  resetMockBranding()
  resetMockOidcProviders()
  // Same reason: the mail settings and templates are mutable so a PUT shows up on the next GET.
  resetMockMailSettings()
  resetMockMailTemplates()
  // Same reason: the local accounts and their settings are mutable, so a lock or a created account
  // shows up on the next GET (#1541).
  resetMockLocalUsers()
  resetMockLocalAuthSettings()
  // The auth config fixture is mutable too (ADR-0033): a test that switches the local account
  // management on must not leave it on for the next one.
  resetMockAuthConfig()
  // A self-service link is single-use in the mock, as it is in the backend - a link one test
  // redeemed would read as invalid in the next.
  resetMockSelfServiceTokens()
  // Dieselbe Begründung für die Fremdzugänge (#1717/#1719): Schalter und Tokenbestand sind
  // veränderlich, damit ein Speichern, ein Anlegen oder eine Sperre im nächsten GET sichtbar ist.
  resetMockExternalAccessSettings()
  resetMockExternalAccessTokens()
  // Prompt libraries, their prompts and the space associations are mutable in the mocks as well.
  resetMockPromptLibraries()
  resetMockSpaceAssetAssociations()
  clearCookies()
})
afterAll(() => server.close())
