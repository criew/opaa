import '@testing-library/jest-dom/vitest'
import { beforeAll, afterEach, afterAll } from 'vitest'
import { server } from '../mocks/server'
import {
  resetIndexingState,
  resetDocumentMockState,
  resetGrantMockState,
  resetChatMockState,
} from '../mocks/handlers'
// Deliberately only the fixture module, never a store or `src/services/api` (#583): importing
// anything that pulls in the axios instance *from this file* makes a dozen unrelated dialog tests
// fail - their requests stop being intercepted, so every "expected onCreated to be called" assertion
// times out. Verified by adding a bare `import '../services/api'` here and watching
// CreateSpaceDialog.test.tsx go red on its own. Store state that has to be reset between tests
// belongs in that test file's own beforeEach.
import { resetMockBranding } from '../mocks/fixtures'

beforeAll(() => server.listen())
afterEach(() => {
  server.resetHandlers()
  resetIndexingState()
  resetDocumentMockState()
  resetGrantMockState()
  resetChatMockState()
  // The branding fixture is mutable so a PUT is visible on the next GET (#583) - without this,
  // a test that configures a brand colour would silently set the stage for the next one.
  resetMockBranding()
})
afterAll(() => server.close())
