import type { AssetOwnerType, AssetRole } from '@/shared/api/types'

/**
 * UI-lab model of an agent (#3670). The API does not exist yet; once the spec carries agents,
 * these types give way to the generated DTOs.
 */
export type AgentToolId =
  'search_knowledge' | 'read_document' | 'list_sources' | 'run_code' | 'ask_user'

/** Whether the run shows its plan first: not at all, for reading, or for confirmation. */
export type AgentPlanStep = 'OFF' | 'SHOW' | 'CONFIRM'

export type AgentOutputKind = 'CHAT' | 'FILE'

export type AgentFileFormat = 'DOCX' | 'PDF'

export interface AgentLimits {
  /** Tool calls per run. */
  calls: number
  /** Run time in minutes. */
  minutes: number
  /** Collected text over all steps, in pages of about 3,000 characters. */
  pages: number
}

export interface AgentConfig {
  name: string
  description: string
  /** The unit answerable for the agent's content (mandatory). */
  responsibleUnit: string
  instruction: string
  libraryIds: string[]
  tools: AgentToolId[]
  limits: AgentLimits
  planStep: AgentPlanStep
  namesContradictions: boolean
  output: AgentOutputKind
  templateIds: string[]
  fileFormat: AgentFileFormat
  /** null: the installation's default tool-capable model. */
  modelId: string | null
}

export interface AgentResponse extends AgentConfig {
  id: string
  builtIn: boolean
  /** Locked by the system administration: listed, but not callable. */
  locked: boolean
  /** Counts up with every saved change; the result's stamp names it. */
  version: number
  myRole: AssetRole
  ownerType: AssetOwnerType
  ownerName: string
  allAccounts: boolean
  updatedAt: string
}

export type ToolCapability = 'CAPABLE' | 'NOT_CAPABLE' | 'UNCHECKED'

export interface AgentModel {
  id: string
  name: string
  toolCapability: ToolCapability
  /** The installation's default model for agents. */
  isDefault: boolean
}

export interface DocumentTemplate {
  id: string
  name: string
  description: string
  placeholders: string[]
  fileName: string
  myRole: AssetRole
  ownerName: string
  updatedAt: string
}

/** The installation's ceilings; an agent may only stay below them. */
export interface AgentSystemLimits {
  max: AgentLimits
  /** What a new agent starts with. */
  defaults: AgentLimits
}
