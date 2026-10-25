/**
 * Shapes of the installation API (ADR-0060), named after the schemas of the OpenAPI fragment
 * `installation.yaml`.
 *
 * Interim until #3189 merges that fragment: these types repeat its schemas by hand. Once it is
 * part of the bundled spec they become aliases of the generated `components['schemas'][...]`.
 */

/** The steps the API lists, in its order; the token is not one of them. */
export type InstallationStepId = 'FIRST_ADMIN' | 'SIGN_IN' | 'CHAT_MODEL'

export type InstallationSignInMode = 'LOCAL_ONLY' | 'LOCAL_AND_OIDC'

export interface InstallationFirstAdmin {
  email: string
  displayName: string
  password: string
}

export interface InstallationFirstAdminView {
  email: string
  displayName: string
  passwordSet: boolean
}

export interface InstallationOidcProvider {
  issuerUri: string
  clientId: string
  jwkSetUri?: string | null
  /** Name on the sign-in page; the backend uses „Verzeichnisdienst" when absent. */
  displayName?: string | null
}

export interface InstallationSignIn {
  mode: InstallationSignInMode
  oidc?: InstallationOidcProvider | null
}

export interface InstallationChatModel {
  baseUrl: string
  model: string
  /** Omitted keeps a key saved before, empty removes it. */
  apiKey?: string | null
  temperature?: number | null
  /** The backend uses 8000 when absent. */
  maxTokens?: number | null
}

export interface InstallationChatModelView {
  baseUrl: string
  model: string
  apiKeySet: boolean
  temperature?: number | null
  maxTokens: number
}

export interface InstallationStepState {
  step: InstallationStepId
  mandatory: boolean
  saved: boolean
  /** Whether the last check of the saved values succeeded. */
  checked: boolean
  /** The German outcome of the last check, absent before the first one. */
  checkMessage?: string | null
  firstAdmin?: InstallationFirstAdminView | null
  signIn?: InstallationSignIn | null
  chatModel?: InstallationChatModelView | null
}

export interface InstallationStatusResponse {
  pending: boolean
  /** Whether every mandatory step is saved and successfully checked. */
  completable: boolean
  steps: InstallationStepState[]
}

/** The values a step's save sends, by step. */
export interface StepInput {
  FIRST_ADMIN: InstallationFirstAdmin
  SIGN_IN: InstallationSignIn
  CHAT_MODEL: InstallationChatModel
}

/** The part of `InstallationStepRequest` that carries a step's values. */
export const STEP_REQUEST_KEY = {
  FIRST_ADMIN: 'firstAdmin',
  SIGN_IN: 'signIn',
  CHAT_MODEL: 'chatModel',
} as const satisfies Record<InstallationStepId, string>
