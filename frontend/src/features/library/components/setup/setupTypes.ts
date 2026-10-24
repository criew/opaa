import type { ComponentType } from 'react'
import type { SourceConnectionTestRequest } from '@/shared/api/types'
import type { SourceIssue } from '../../utils/sourceIssue'
import type { ConnectionFields } from '../sources/sourceConnection'
import type { SourceFormContext } from '../sources/types'

/** The rows of the source setup: how the source is reached, what is read from it, when. */
export type SetupRowId = 'connection' | 'scope' | 'schedule'

/** Where the connection check stands for the values as they are now. */
export type CheckState = 'unchecked' | 'checking' | 'passed' | 'partial' | 'failed'

export interface SetupFieldsProps<V> {
  values: V
  onChange: (patch: Partial<V>) => void
  context: SourceFormContext
  fields: ConnectionFields
  /** The message of the current issue at `field`, null without one. */
  issueAt: (field: string) => string | null
  /** The id of the control that edits `field`; the error summary jumps to it. */
  fieldId: (field: string) => string
}

/** One row of a connector's setup: its summary while closed and its fields while open. */
export interface SetupSectionSpec<V> {
  id: Exclude<SetupRowId, 'schedule'>
  /** The one-line summary; null while nothing is set yet. */
  summary: (values: V, context: SourceFormContext, check: CheckState) => string | null
  Fields: ComponentType<SetupFieldsProps<V>>
}

/**
 * How one connector fills the rows „Verbindung“ and „Umfang“. Registered next to the connector's
 * form in the registry; the rows, the connection check, the error summary and the schedule are
 * shared and know nothing of the connector.
 */
export interface SourceSetupSpec<V> {
  sections: SetupSectionSpec<V>[]
  /** Every issue of the values at once, each naming the field it concerns. */
  issues: (values: V, context: SourceFormContext) => SourceIssue[]
  /** The row an issue's field belongs to. */
  rowOf: (field: string) => Exclude<SetupRowId, 'schedule'>
  /** The connection test of the values; absent for a source without one. */
  probe?: (values: V, fields: ConnectionFields, context: SourceFormContext) => SourceConnectionTestRequest
  /** Whether the source signs in at all; without, the check skips the stage „Anmeldung“. */
  signsIn?: boolean
}
