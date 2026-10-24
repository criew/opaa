import { DriveManualEntry } from './ScopeExtras'
import { browseSource } from '../../../api/libraryApi'
import { readGoogleDriveKey } from '@/features/connection'
import {
  GOOGLE_DRIVE_API,
  googleDriveScopeFromKey,
  googleDriveScopeKey,
  googleDriveScopeKindLabel,
  googleDriveScopeLabel,
  storedKeyServes,
  type GoogleDriveScope,
  type GoogleDriveSourceValues,
} from '../../../utils/googleDriveSource'
import { checkByListing } from '../connectionCheck'
import type { FieldErrors, FieldSpec, ScopeNode, StepContext, StepwiseSource } from '../types'

export function driveNode(scope: GoogleDriveScope): ScopeNode {
  return {
    id: googleDriveScopeKey(scope),
    name: googleDriveScopeLabel(scope),
    ancestors: [],
    trail: [],
    expandable: false,
    selectable: true,
    meta: googleDriveScopeKindLabel(scope.kind),
  }
}

export function driveScopeOfNode(node: ScopeNode): GoogleDriveScope | null {
  return googleDriveScopeFromKey(node.id, node.name)
}

function listing(values: GoogleDriveSourceValues, ctx: StepContext) {
  return browseSource(
    'GOOGLE_DRIVE',
    ctx.fields.probeRequest({
      sourceUrl: GOOGLE_DRIVE_API,
      sourceCredentials: values.keyFile || undefined,
      query: { subject: values.subject.trim() || undefined },
    }),
  )
}

export const googleDriveStepwise: StepwiseSource<GoogleDriveSourceValues> = {
  connectionHeading: 'Verbindung zu Google Drive',
  fields: (_values, ctx) => {
    const specs: FieldSpec[] = []
    if (ctx.fields.asksSecret) {
      specs.push({
        key: 'keyFile',
        label: 'Schlüsseldatei',
        kind: 'file',
        accept: 'application/json,.json',
        credential: true,
        hint: 'JSON-Schlüssel des Dienstkontos aus der Google Cloud Console.',
        readFile: (text) => {
          const read = readGoogleDriveKey(text)
          return 'error' in read ? { error: read.error } : { patch: { keyFile: text, keyAccount: read.account } }
        },
        fileName: (values) => (values.keyAccount ? String(values.keyAccount) : null),
      })
    }
    specs.push({
      key: 'subject',
      label: 'Imitiertes Konto',
      kind: 'text',
      optional: true,
      inputType: 'email',
      placeholder: 'name@example.de',
      hint: 'Nur nötig für „Meine Ablage“ eines Kontos.',
      fixed: ctx.fields.isFixed('subject'),
    })
    return specs
  },
  connectionErrors: (values, ctx) => {
    const errors: FieldErrors = {}
    const keyServes =
      !ctx.fields.asksSecret ||
      values.keyFile !== '' ||
      (ctx.mode === 'edit' && !ctx.replacingCredentials && storedKeyServes(values, ctx.credentialsStored))
    if (!keyServes) {
      errors.keyFile =
        ctx.mode === 'edit'
          ? 'Für ein anderes imitiertes Konto bitte die Schlüsseldatei neu wählen.'
          : 'Bitte die Schlüsseldatei wählen.'
    }
    const subject = values.subject.trim()
    if (subject !== '' && !/^[^@\s]+@[^@\s]+$/.test(subject)) {
      errors.subject = 'Das imitierte Konto ist eine E-Mail-Adresse, z. B. name@example.de.'
    }
    return errors
  },
  stageFields: { address: [], signIn: ['keyFile', 'subject'] },
  check: (values, ctx) => checkByListing(() => listing(values, ctx), 'Bereiche'),
  connectionRows: (values, { credentialsStored }) => [
    {
      label: 'Schlüsseldatei',
      value: values.keyAccount || (credentialsStored ? 'Gesetzt' : 'Keine'),
    },
    { label: 'Imitiertes Konto', value: values.subject.trim() || 'Keines' },
  ],
  scope: {
    heading: 'Welche Bereiche soll die Bibliothek lesen?',
    noun: ['Bereich', 'Bereiche'],
    hierarchical: false,
    load: async (_parent, values, ctx) => {
      const result = await listing(values, ctx)
      return {
        nodes: result.entries
          .map((entry) => googleDriveScopeFromKey(entry.key, entry.name ?? null))
          .filter((scope): scope is GoogleDriveScope => scope !== null)
          .map(driveNode),
        complete: result.complete,
        message: result.message,
      }
    },
    selection: (values) => values.scopes.map(driveNode),
    withSelection: (_values, nodes) => ({
      scopes: nodes.map(driveScopeOfNode).filter((scope): scope is GoogleDriveScope => scope !== null),
    }),
    max: 50,
    Manual: DriveManualEntry,
    errors: (values) => {
      if (values.scopes.length === 0) return { scope: 'Bitte mindestens einen Bereich wählen.' }
      if (values.scopes.some((scope) => scope.kind === 'myDrive') && values.subject.trim() === '') {
        return { scope: '„Meine Ablage“ setzt ein imitiertes Konto voraus.' }
      }
      return {}
    },
    failedEntries: (response) => {
      const scopes = (response.details?.scopes ?? []) as Array<{
        scope: string
        reachable: boolean
        message?: string | null
      }>
      return Object.fromEntries(
        scopes.filter((s) => !s.reachable).map((s) => [s.scope, s.message ?? 'nicht erreichbar']),
      )
    },
  },
  scopeRows: (values) => [
    {
      label: 'Bereiche',
      value: `${values.scopes.length} ${values.scopes.length === 1 ? 'Bereich' : 'Bereiche'}`,
      list: values.scopes.map(googleDriveScopeLabel),
    },
  ],
}
