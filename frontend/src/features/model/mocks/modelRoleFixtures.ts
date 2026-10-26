import type {
  RerankModelSettingsResponse,
  TranscriptionModelSettingsResponse,
} from '@/shared/api/types'

/** The factory default of both roles: off, unbound, no key. */
export function factoryRerankModel(): RerankModelSettingsResponse {
  return {
    enabled: false,
    baseUrl: null,
    model: null,
    apiKeySet: false,
    state: 'DISABLED',
    updatedAt: null,
    retiredVariables: [],
  }
}

export function factoryTranscriptionModel(): TranscriptionModelSettingsResponse {
  return {
    enabled: false,
    baseUrl: null,
    model: null,
    apiKeySet: false,
    language: 'de',
    state: 'DISABLED',
    speechInputAvailable: true,
    updatedAt: null,
    retiredVariables: [],
  }
}

export const mockModelRoles = {
  rerank: factoryRerankModel(),
  transcription: factoryTranscriptionModel(),
}

export function resetMockModelRoles() {
  mockModelRoles.rerank = factoryRerankModel()
  mockModelRoles.transcription = factoryTranscriptionModel()
}
