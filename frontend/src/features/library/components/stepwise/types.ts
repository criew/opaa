import type { ComponentType } from 'react'
import type { SourceConnectionTestResponse, SourceTypeKey } from '@/shared/api/types'
import type { LibrarySourceConfigPayload } from '../../utils/librarySourceConfig'
import type { ConnectionFields } from '../sources/sourceConnection'
import type { SourceFormContext, StoredLibrarySource } from '../sources/types'

/** Messages by field key; the key `scope` stands for the whole selection. */
export type FieldErrors = Partial<Record<string, string>>

/** One input of the page „Verbindung“, rendered by the one shared field list. */
export interface FieldSpec {
  key: string
  label: string
  kind: 'text' | 'secret' | 'multiline' | 'file' | 'choice'
  optional?: boolean
  placeholder?: string
  /** One line that says something the label does not. */
  hint?: string
  mono?: boolean
  /** Part of the stored credentials: in edit mode collapsed into „Zugangsdaten · gesetzt“. */
  credential?: boolean
  options?: { value: string; label: string }[]
  /** `file`: what the input accepts and how a read file becomes field values. */
  accept?: string
  readFile?: (text: string) => { patch: Record<string, unknown> } | { error: string }
  /** `file`: what is chosen, shown next to the button. */
  fileName?: (values: Record<string, unknown>) => string | null
  autoComplete?: string
  /** Set by the connection profile: shown, not editable. */
  fixed?: boolean
  inputType?: 'email' | 'url'
}

/** Everything a stepwise page derives from where it is shown. */
export interface StepContext {
  mode: 'create' | 'edit'
  sourceType: SourceTypeKey
  credentialsStored: boolean
  /** In edit mode: the person chose „Ersetzen“ for the stored credentials. */
  replacingCredentials: boolean
  fields: ConnectionFields
  formContext: SourceFormContext
}

/** The three stages every connection check reports in this order. */
export type CheckStage = 'address' | 'signIn' | 'rights'
/** `none`: the stage does not exist for the source (a file system has no sign-in). */
export type StageState = 'ok' | 'failed' | 'limited' | 'open' | 'none'

export interface CheckOutcome {
  stages: Record<CheckStage, StageState>
  /** The connector's own sentence, German, safe to show. */
  message: string
  /** Whether the next step may follow without asking. */
  ok: boolean
  /** Per selected entry (scope id) the reason it cannot be read. */
  failedEntries?: Record<string, string>
  /** What the check learned about the source, e.g. the Confluence edition. */
  patch?: Record<string, unknown>
}

/** One foreign object of a source: a folder, a space, a bucket, a site. */
export interface ScopeNode {
  id: string
  name: string
  /** Ids of the ancestors, outermost first. */
  ancestors: string[]
  /** Names of the ancestors, for search hits and the selection summary. */
  trail: string[]
  expandable: boolean
  selectable: boolean
  /** A second piece of information, e.g. the kind of a Google Drive area. */
  meta?: string
}

export interface ScopeListing {
  nodes: ScopeNode[]
  complete: boolean
  message?: string | null
}

/** The page „Umfang“ of a source: what it lists and how a selection is stored. */
export interface StepwiseScope<V> {
  heading: string
  /** Singular and plural of the objects („Ordner“, „Spaces“). */
  noun: [string, string]
  hierarchical: boolean
  /** The option „everything“ with its label, for sources where reading all is the default. */
  whole?: {
    label: string
    partLabel: string
    isWhole: (values: V) => boolean
    set: (values: V, whole: boolean) => Partial<V>
  }
  load: (parent: ScopeNode | null, values: V, ctx: StepContext) => Promise<ScopeListing>
  selection: (values: V) => ScopeNode[]
  withSelection: (values: V, nodes: ScopeNode[]) => Partial<V>
  max: number
  errors: (values: V, ctx: StepContext) => FieldErrors
  /** The selected entries the connection test could not read, by scope id. */
  failedEntries?: (response: SourceConnectionTestResponse, values: V) => Record<string, string>
  /** Entry by hand where a listing is refused (S3 bucket, Drive folder id). */
  Manual?: ComponentType<{ values: V; onChange: (patch: Partial<V>) => void; idPrefix: string }>
  /** Settings of the scope besides the selection (Dropbox team space). */
  Extra?: ComponentType<{ values: V; onChange: (patch: Partial<V>) => void; idPrefix: string }>
}

/**
 * How a source type is configured one thing per page (variant B of #3084): the page
 * „Verbindung“ with its fields and check, then - if the source lists foreign objects - the page
 * „Umfang“. Registered next to the classic form in the registry.
 */
export interface StepwiseSource<V> {
  /** The heading of the page „Verbindung“, e.g. „Verbindung zur Nextcloud“. */
  connectionHeading: string
  fields: (values: V, ctx: StepContext) => FieldSpec[]
  connectionErrors: (values: V, ctx: StepContext) => FieldErrors
  /** Fields a failed stage marks; the address stage also stands for the path of a file system. */
  stageFields: { address: string[]; signIn: string[] }
  /** The check behind „Weiter“ and „Verbindung prüfen“; defaults to a connection test. */
  check?: (values: V, ctx: StepContext) => Promise<CheckOutcome>
  /** The payload the default check sends. */
  checkPayload?: (values: V) => LibrarySourceConfigPayload
  /** Completes a field change, e.g. the endpoint a chosen provider implies. */
  adjust?: (patch: Partial<V>, values: V) => Partial<V>
  /** Whether the source has a sign-in stage; a file system has none. */
  signsIn?: boolean
  /** The rows of the summary (Prüfen, Reiter „Quelle“) for the connection, without credentials. */
  connectionRows: (values: V, ctx: { credentialsStored: boolean }) => SummaryRow[]
  scope?: StepwiseScope<V>
  /** The rows of the scope summary for a stored library. */
  scopeRows?: (values: V) => SummaryRow[]
}

export interface SummaryRow {
  label: string
  value: string
  mono?: boolean
  /** Many values (folders): the summary shows the first few and „und n weitere“. */
  list?: string[]
}

export type StoredSource = StoredLibrarySource
