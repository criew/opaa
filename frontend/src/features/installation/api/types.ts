/**
 * Shapes of the installation API (ADR-0060, spec docs/features/installation-and-settings.md).
 *
 * Interim until #3189 adds the OpenAPI fragment `installation.yaml`: these types follow the API
 * sketch of the spec. Once the fragment exists they become aliases of the generated
 * `components['schemas'][...]`, and only this file and the mocks change.
 */

/** The steps of the assistant in the order the API lists them; the token is not one of them. */
export type InstallationStepId =
  | 'FIRST_ADMIN'
  | 'SIGN_IN'
  | 'CHAT_MODEL'
  | 'EMBEDDING_MODEL'
  | 'OUTBOUND_PROXY'
  | 'MAIL'
  | 'BRANDING'

export type SignInMode = 'LOCAL_ONLY' | 'LOCAL_AND_OIDC'

export interface FirstAdminValues {
  email: string
  displayName: string
  /** Write-only; omitted on save to keep the stored password. */
  password?: string
}

export interface SignInValues {
  mode: SignInMode
  oidc: { issuerUri: string; clientId: string; jwkSetUri: string | null } | null
}

export interface ChatModelValues {
  baseUrl: string
  model: string
  /** Write-only; omitted on save to keep the stored key, `''` removes it. */
  apiKey?: string
  temperature: number | null
  maxTokens: number
}

export interface BrandingValues {
  productName: string
  primaryColor: string
}

/** What a stored step reads back: its values without secrets, secrets only as „gesetzt“. */
export interface StoredStepValues {
  FIRST_ADMIN: Omit<FirstAdminValues, 'password'> & { passwordSet: boolean }
  SIGN_IN: SignInValues
  CHAT_MODEL: Omit<ChatModelValues, 'apiKey'> & { apiKeySet: boolean }
  BRANDING: BrandingValues
}

export interface StepInput {
  FIRST_ADMIN: FirstAdminValues
  SIGN_IN: SignInValues
  CHAT_MODEL: ChatModelValues
  BRANDING: BrandingValues
}

/** The steps this assistant has a form for. */
export type SupportedStepId = keyof StepInput

export interface InstallationStep {
  step: InstallationStepId
  required: boolean
  /** The step's values are stored. */
  saved: boolean
  /** The check of the stored values succeeded; a changed value resets it. */
  checked: boolean
  /** The stored values, `null` while nothing is stored. */
  values: Record<string, unknown> | null
}

export interface InstallationState {
  steps: InstallationStep[]
}
