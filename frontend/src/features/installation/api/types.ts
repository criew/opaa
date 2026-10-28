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

export type InstallationEmbeddingModel = Schemas['InstallationEmbeddingModel']
export type InstallationEmbeddingModelView = Schemas['InstallationEmbeddingModelView']

export type InstallationRerankModel = Schemas['InstallationRerankModel']
export type InstallationRerankModelView = Schemas['InstallationRerankModelView']

export type InstallationMail = Schemas['InstallationMail']
export type InstallationMailView = Schemas['InstallationMailView']

/** The branding without images; sent and returned alike, `null` meaning the OPAA default. */
export type InstallationBranding = Schemas['InstallationBranding']

/** Options of a check; only the mail step reads one. */
export type InstallationStepCheckRequest = Schemas['InstallationStepCheckRequest']

/** The steps the API lists, in its order; the token is not one of them. */
export type InstallationStepId = Schemas['InstallationStep']

export type InstallationStepState = Schemas['InstallationStepState']

export type InstallationStatusResponse = Schemas['InstallationStatusResponse']

export type InstallationModelDiscoveryRequest = Schemas['InstallationModelDiscoveryRequest']

/** The steps whose endpoint lists its models. */
export type ModelStepId = Extract<
  InstallationStepId,
  'CHAT_MODEL' | 'EMBEDDING_MODEL' | 'RERANK_MODEL'
>

/** The model steps in the order of the API. */
export const MODEL_STEPS: readonly ModelStepId[] = ['CHAT_MODEL', 'EMBEDDING_MODEL', 'RERANK_MODEL']

/** The values a step's save sends, by step. */
export interface StepInput {
  FIRST_ADMIN: InstallationFirstAdmin
  SIGN_IN: InstallationSignIn
  CHAT_MODEL: InstallationChatModel
  EMBEDDING_MODEL: InstallationEmbeddingModel
  RERANK_MODEL: InstallationRerankModel
  MAIL: InstallationMail
  BRANDING: InstallationBranding
}

/** The part of `InstallationStepRequest` that carries a step's values. */
export const STEP_REQUEST_KEY = {
  FIRST_ADMIN: 'firstAdmin',
  SIGN_IN: 'signIn',
  CHAT_MODEL: 'chatModel',
  EMBEDDING_MODEL: 'embeddingModel',
  RERANK_MODEL: 'rerankModel',
  MAIL: 'mail',
  BRANDING: 'branding',
} as const satisfies Record<InstallationStepId, keyof Schemas['InstallationStepRequest']>
