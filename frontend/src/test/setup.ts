import '@testing-library/jest-dom/vitest'
// Must run before any request is made: it closes the jsdom gap that makes a `FormData` upload
// carrying a `File` hang forever instead of completing (#1169).
import './jsdom-blob-stream'
import { beforeAll, afterEach, afterAll } from 'vitest'
import { server } from '../mocks/server'
import {
  resetIndexingState,
  resetDocumentMockState,
  resetGrantMockState,
  resetChatMockState,
  resetLlmModelMockState,
} from '../mocks/handlers'
// Deliberately only the fixture module, never a store or `src/services/api` (#583): importing
// anything that pulls in the axios instance *from this file* makes a dozen unrelated dialog tests
// fail - their requests stop being intercepted, so every "expected onCreated to be called" assertion
// times out. Verified by adding a bare `import '../services/api'` here and watching
// SpaceCreatePage.test.tsx go red on its own. Store state that has to be reset between tests
// belongs in that test file's own beforeEach.
import { resetMockAuthConfig, resetMockBranding, resetMockOidcProviders } from '../mocks/fixtures'
import { resetMockMailSettings, resetMockMailTemplates } from '../mocks/mailFixtures'
import { resetMockLocalAuthSettings, resetMockLocalUsers } from '../mocks/localUserFixtures'

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
  clearCookies()
})
afterAll(() => server.close())
