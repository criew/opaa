import type { ComponentType } from 'react'
import type { LibrarySchedule, SourceTypeKey } from '../../../types/api'
import type { LibrarySourceConfigPayload } from '../../../utils/librarySourceConfig'
import type { SourceConnection } from './sourceConnection'

/** The stored library as the source registrations read it - never a credential, only whether one is set. */
export interface StoredLibrarySource {
  sourceType: SourceTypeKey
  sourcePath?: string | null
  sourceUrl?: string | null
  sourceProxy?: string | null
  sourceInsecureSsl?: boolean | null
  sourceCredentialsSet?: boolean | null
  sourceSettings?: Record<string, unknown> | null
  pushSecretSet?: boolean | null
  fullSyncIntervalDefaultDays?: number | null
  schedule?: LibrarySchedule | null
}

/** Where a form is shown and what it may fall back to. */
export interface SourceFormContext {
  mode: 'create' | 'edit'
  sourceType: SourceTypeKey
  /** The id prefix of the surrounding dialog or wizard step; a form may append its own suffix. */
  idPrefix: string
  /** Edit mode: the library, so tests and listings may fall back to its stored credentials. */
  libraryId?: string
  /** Edit mode: whether credentials are stored for the library. */
  credentialsStored: boolean
  /** Edit mode: the address the stored credentials belong to - they do not survive a host change. */
  originalSourceUrl?: string | null
  /**
   * The connection profile the library is created or kept on; absent for its own address. A form
   * reads it only through `connectionFields`.
   */
  connection?: SourceConnection
  /** The library runs on the caller's own connected account on `connection`: no secret of its own. */
  privateLibrary?: boolean
}

export interface SourceFormProps<V> {
  values: V
  onChange: (patch: Partial<V>) => void
  context: SourceFormContext
}

/** The one-line Umfang in the page head and the warning about a scope the last run could not read. */
export interface SourceScopeHero {
  summary: (library: StoredLibrarySource) => string
  unlistedWarning: (keys: string[], library: StoredLibrarySource) => string
  unlistedTestId: string
}

/**
 * Everything the frontend knows about one connector's configuration, registered once under its
 * type key (ADR-0038): the form with its starting values, validation and request fields, and the
 * read views of the Reiter „Quelle".
 */
export interface SourceConfiguration<V> {
  /** The values of a new library. */
  empty: V
  /**
   * The wizard keeps entered values under this key; types sharing a form shape share it, so an
   * address survives a change between them. Defaults to the type key.
   */
  valuesKey?: string
  /**
   * The fields the form reads from the server behind the address; they belong to that server and
   * fall back to `empty` whenever the wizard clears the address.
   */
  addressDerived?: readonly string[]
  /** The values of a stored library; credentials stay blank, they are never returned. */
  fromLibrary: (library: StoredLibrarySource) => V
  /** Whether a wizard holding these values has something to lose. */
  isDirty: (values: V) => boolean
  /** The German rejection of the values, or null when they may be sent. */
  validate: (values: V, context: SourceFormContext) => string | null
  /** The source fields of a LibraryRequest/LibraryUpdateRequest; call {@link validate} first. */
  toPayload: (values: V) => LibrarySourceConfigPayload
  /** The name the source already suggests for a new library, '' for none. */
  nameFromSource: (values: V) => string
  /** The form, including its own connection test. */
  Form: ComponentType<SourceFormProps<V>>
  /** The stored connection as the Bereich „Anbindung" shows it to a manager. */
  StoredView: ComponentType<{ library: StoredLibrarySource; libraryId: string }>
  /** The scope every reader sees in the Bereich „Umfang"; absent for a source without one. */
  Scope?: ComponentType<{ library: StoredLibrarySource }>
  scopeHero?: SourceScopeHero
  /**
   * Whether runs come in two modes - incremental and a forcible full reconciliation with its own
   * rhythm (Confluence).
   */
  fullSyncRhythm?: boolean
  /**
   * The stored settings a change of the rhythm resends whole, for a connector that replaces its
   * settings as a whole instead of keeping the parts a request leaves out (Google Drive).
   */
  rhythmSettingsBase?: (library: StoredLibrarySource) => Record<string, unknown> | null
  /** The sentence under the wizard's immediate-first-run switch. */
  firstRunHint?: string
  /** The explanation above the run history. */
  runsHint?: string
}

/** One source type as the frontend presents it. */
export interface SourceRegistration {
  label: string
  shortLabel: string
  description: string
  Icon: ComponentType<{ sx?: object }>
  /** What a document row calls its container key (a Confluence space, an S3 bucket). */
  containerLabel?: string
  /** Whether a document's original is the page at the source, opened there rather than served. */
  opensAtSource?: boolean
  /** Whether a run counts the entries of a feed rather than documents alone (RSS). */
  runCountsEntries?: boolean
  /** Null for UPLOAD, which has no source to configure. */
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  configuration: SourceConfiguration<any> | null
}
