import '@testing-library/jest-dom/vitest'
import { beforeAll, afterEach, afterAll } from 'vitest'
import { server } from '../mocks/server'
import {
  resetIndexingState,
  resetDocumentMockState,
  resetGrantMockState,
  resetChatMockState,
} from '../mocks/handlers'

beforeAll(() => server.listen())
afterEach(() => {
  server.resetHandlers()
  resetIndexingState()
  resetDocumentMockState()
  resetGrantMockState()
  resetChatMockState()
})
afterAll(() => server.close())
