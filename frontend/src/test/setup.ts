import '@testing-library/jest-dom/vitest'
import { beforeAll, afterEach, afterAll } from 'vitest'
import { server } from '../mocks/server'
import { resetIndexingState, resetDocumentMockState } from '../mocks/handlers'

beforeAll(() => server.listen())
afterEach(() => {
  server.resetHandlers()
  resetIndexingState()
  resetDocumentMockState()
})
afterAll(() => server.close())
