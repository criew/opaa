import type { components } from '@/shared/api/generated/api'

/** Shapes of the installation API (ADR-0060), from the OpenAPI fragment `installation.yaml`. */
type Schemas = components['schemas']

export type InstallationSignInMode = Schemas['InstallationSignInMode']
export type InstallationFirstAdmin = Schemas['InstallationFirstAdmin']
export type InstallationFirstAdminView = Schemas['InstallationFirstAdminView']
export type InstallationOidcProvider = Schemas['InstallationOidcProvider']
export type InstallationSignIn = Schemas['InstallationSignIn']
export type InstallationChatModel = Schemas['InstallationChatModel']
export type InstallationChatModelView = Schemas['InstallationChatModelView']

/**
 * The embedding model step. Interim until #3191 adds it to the fragment: written by hand after the
 * schemas `InstallationEmbeddingModel` and `InstallationEmbeddingModelView` of that change; then
 * these become aliases and `EMBEDDING_MODEL` comes with the generated step enum.
 */
export interface InstallationEmbeddingModel {
  baseUrl: string
  model: string
  /** Omitted keeps a key saved before, empty removes it. */
  apiKey?: string | null
  /** Input limit of the endpoint in tokens; the backend uses 2048 when absent. */
  maxInputTokens?: number | null
}

export interface InstallationEmbeddingModelView {
  baseUrl: string
  model: string
  apiKeySet: boolean
  maxInputTokens: number
  /** Vector size of the last successful probe embedding; absent until the check succeeded. */
  dimensions?: number | null
}

/** The steps the API lists, in its order; the token is not one of them. */
export type InstallationStepId = Schemas['InstallationStep'] | 'EMBEDDING_MODEL'

export type InstallationStepState = Omit<Schemas['InstallationStepState'], 'step'> & {
  step: InstallationStepId
  embeddingModel?: InstallationEmbeddingModelView | null
}

export type InstallationStatusResponse = Omit<Schemas['InstallationStatusResponse'], 'steps'> & {
  steps: InstallationStepState[]
}

/** The values a step's save sends, by step. */
export interface StepInput {
  FIRST_ADMIN: InstallationFirstAdmin
  SIGN_IN: InstallationSignIn
  CHAT_MODEL: InstallationChatModel
  EMBEDDING_MODEL: InstallationEmbeddingModel
}

/** The part of `InstallationStepRequest` that carries a step's values. */
export const STEP_REQUEST_KEY = {
  FIRST_ADMIN: 'firstAdmin',
  SIGN_IN: 'signIn',
  CHAT_MODEL: 'chatModel',
  EMBEDDING_MODEL: 'embeddingModel',
} as const satisfies Record<
  InstallationStepId,
  keyof Schemas['InstallationStepRequest'] | 'embeddingModel'
>
