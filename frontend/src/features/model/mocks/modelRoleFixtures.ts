import type {
  RerankModelSettingsResponse,
  TextRecognitionModelSettingsResponse,
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
    vocabulary: '',
    state: 'DISABLED',
    speechInputAvailable: true,
    updatedAt: null,
    retiredVariables: [],
  }
}

/** Off and unbound; the operation has released text recognition, so the form is shown. */
export function factoryTextRecognitionModel(): TextRecognitionModelSettingsResponse {
  return {
    enabled: false,
    connectionType: 'DOCLING_SERVE',
    baseUrl: null,
    model: null,
    apiKeySet: false,
    state: 'DISABLED',
    textRecognitionAvailable: true,
    updatedAt: null,
  }
}

/** The shipped OCR service, switched on, with a stored key. */
export function configuredTextRecognitionModel(): TextRecognitionModelSettingsResponse {
  return {
    ...factoryTextRecognitionModel(),
    enabled: true,
    baseUrl: 'http://opaa-ocr:5001',
    apiKeySet: true,
    state: 'ACTIVE',
    updatedAt: '2026-11-05T09:30:00Z',
  }
}

export const mockModelRoles = {
  rerank: factoryRerankModel(),
  transcription: factoryTranscriptionModel(),
  textRecognition: factoryTextRecognitionModel(),
}

export function resetMockModelRoles() {
  mockModelRoles.rerank = factoryRerankModel()
  mockModelRoles.transcription = factoryTranscriptionModel()
  mockModelRoles.textRecognition = factoryTextRecognitionModel()
}
