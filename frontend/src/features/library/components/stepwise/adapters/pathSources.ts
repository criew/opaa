import { DropboxTeamSwitch } from './ScopeExtras'
import type { SourceTypeKey } from '@/shared/api/types'
import { browseSource } from '../../../api/libraryApi'
import {
  nextcloudCredentialsOf,
  type NextcloudSourceValues,
} from '../../../utils/nextcloudSource'
import { smbCredentialsOf, type SmbSourceValues } from '../../../utils/smbSource'
import { DROPBOX_API, type DropboxSourceValues } from '../../../utils/dropboxSource'
import type { FieldErrors, FieldSpec, StepContext, StepwiseScope, StepwiseSource } from '../types'
import {
  addressError,
  asksCredentials,
  credentialsRow,
  linesOf,
  pathNode,
} from './common'

type FolderValues = { folders: string }

/**
 * The folder tree of a source that lists folders by path (Nextcloud, Windows-Dateifreigabe,
 * Dropbox): „Alle Ordner“ by default, else a tree that opens level by level.
 */
function folderScope<V extends FolderValues>(
  sourceType: SourceTypeKey,
  request: (values: V) => { sourceUrl: string; sourceCredentials?: string },
  extraQuery: (values: V) => Record<string, unknown> = () => ({}),
  wholeLabel = 'Alle Ordner',
): StepwiseScope<V> {
  return {
    heading: 'Welche Ordner soll die Bibliothek lesen?',
    noun: ['Ordner', 'Ordner'],
    hierarchical: true,
    whole: {
      label: wholeLabel,
      partLabel: 'Nur ausgewählte Ordner',
      isWhole: (values) => linesOf(values.folders).includes('/'),
      set: (_values, whole) => ({ folders: whole ? '/' : '' }) as Partial<V>,
    },
    load: async (parent, values, ctx: StepContext) => {
      const result = await browseSource(
        sourceType,
        ctx.fields.probeRequest({
          ...request(values),
          query: { path: parent?.id ?? '/', ...extraQuery(values) },
        }),
      )
      return {
        nodes: result.entries.map((entry) => pathNode(entry.key, entry.name)),
        complete: result.complete,
        message: result.message,
      }
    },
    selection: (values) =>
      linesOf(values.folders)
        .filter((folder) => folder !== '/')
        .map((folder) => pathNode(folder)),
    withSelection: (_values, nodes) => ({ folders: nodes.map((node) => node.id).join('\n') }) as Partial<V>,
    max: 50,
    errors: (values) => {
      const errors: FieldErrors = {}
      if (linesOf(values.folders).length === 0) {
        errors.scope = 'Bitte mindestens einen Ordner wählen oder „Alle Ordner“.'
      }
      return errors
    },
    failedEntries: (response) => {
      const folders = (response.details?.folders ?? []) as Array<{
        folder: string
        reachable: boolean
        message?: string | null
      }>
      return Object.fromEntries(
        folders.filter((f) => !f.reachable).map((f) => [f.folder, f.message ?? 'Zugriff verweigert.']),
      )
    },
  }
}

function folderRows(values: FolderValues) {
  const folders = linesOf(values.folders)
  return folders.includes('/')
    ? [{ label: 'Ordner', value: 'Alle Ordner' }]
    : [{ label: 'Ordner', value: `${folders.length} Ordner`, list: folders, mono: true }]
}

export const nextcloudStepwise: StepwiseSource<NextcloudSourceValues> = {
  connectionHeading: 'Verbindung zur Nextcloud',
  fields: (_values, ctx) => {
    const specs: FieldSpec[] = [
      {
        key: 'sourceUrl',
        label: 'Adresse',
        kind: 'text',
        placeholder: 'https://cloud.example.de',
        mono: true,
        hint: ctx.fields.addressHint ?? undefined,
      },
    ]
    if (ctx.fields.asksSecret) {
      specs.push(
        { key: 'username', label: 'Benutzername', kind: 'text', credential: true, autoComplete: 'off' },
        {
          key: 'appPassword',
          label: 'App-Passwort',
          kind: 'secret',
          credential: true,
          hint: 'Erzeugt in Nextcloud unter Einstellungen › Sicherheit.',
        },
      )
    }
    return specs
  },
  connectionErrors: (values, ctx) => {
    const errors: FieldErrors = {}
    addressError(values.sourceUrl, errors)
    if (asksCredentials(ctx)) {
      if (values.username.trim() === '') errors.username = 'Bitte den Benutzernamen eingeben.'
      else if (values.username.includes(':')) errors.username = 'Der Benutzername enthält keinen Doppelpunkt.'
      if (values.appPassword === '') errors.appPassword = 'Bitte das App-Passwort eingeben.'
    }
    return errors
  },
  stageFields: { address: ['sourceUrl'], signIn: ['username', 'appPassword'] },
  checkPayload: (values) => ({
    sourceUrl: values.sourceUrl.trim(),
    sourceCredentials: nextcloudCredentialsOf(values),
    sourceSettings: { folders: ['/'] },
  }),
  connectionRows: (values, { credentialsStored }) => [
    { label: 'Adresse', value: values.sourceUrl, mono: true },
    credentialsRow(credentialsStored, values.username !== ''),
  ],
  scope: folderScope<NextcloudSourceValues>('NEXTCLOUD', (values) => ({
    sourceUrl: values.sourceUrl.trim(),
    sourceCredentials: nextcloudCredentialsOf(values),
  })),
  scopeRows: folderRows,
}

export const smbStepwise: StepwiseSource<SmbSourceValues> = {
  connectionHeading: 'Verbindung zur Dateifreigabe',
  fields: () => [
    {
      key: 'sourceUrl',
      label: 'Adresse',
      kind: 'text',
      placeholder: 'smb://server/freigabe',
      mono: true,
      hint: 'Auch als \\\\server\\freigabe.',
    },
    { key: 'account', label: 'Benutzername', kind: 'text', credential: true, hint: 'Mit Domäne, z. B. RATHAUS\\svc-opaa.' },
    { key: 'password', label: 'Passwort', kind: 'secret', credential: true },
  ],
  connectionErrors: (values, ctx) => {
    const errors: FieldErrors = {}
    const url = values.sourceUrl.trim()
    if (url === '') errors.sourceUrl = 'Bitte die Adresse eingeben.'
    else if (!/^(smb:\/\/[^/\s]+\/[^/\s]+|\\\\[^\\\s]+\\[^\\\s]+)/i.test(url)) {
      errors.sourceUrl = 'Die Adresse hat die Form smb://server/freigabe.'
    }
    if (asksCredentials(ctx)) {
      if (values.account.trim() === '') errors.account = 'Bitte den Benutzernamen eingeben.'
      if (values.password === '') errors.password = 'Bitte das Passwort eingeben.'
    }
    return errors
  },
  stageFields: { address: ['sourceUrl'], signIn: ['account', 'password'] },
  checkPayload: (values) => ({
    sourceUrl: values.sourceUrl.trim(),
    sourceCredentials: smbCredentialsOf(values),
    sourceSettings: { folders: ['/'] },
  }),
  connectionRows: (values, { credentialsStored }) => [
    { label: 'Adresse', value: values.sourceUrl, mono: true },
    credentialsRow(credentialsStored, values.account !== ''),
  ],
  scope: folderScope<SmbSourceValues>(
    'SMB',
    (values) => ({ sourceUrl: values.sourceUrl.trim(), sourceCredentials: smbCredentialsOf(values) }),
    () => ({}),
    'Die ganze Freigabe',
  ),
  scopeRows: folderRows,
}

export const dropboxStepwise: StepwiseSource<DropboxSourceValues> = {
  connectionHeading: 'Verbindung zu Dropbox',
  fields: () => [],
  connectionErrors: () => ({}),
  stageFields: { address: [], signIn: [] },
  checkPayload: () => ({ sourceUrl: DROPBOX_API, sourceSettings: { folders: ['/'] } }),
  connectionRows: () => [],
  scope: {
    ...folderScope<DropboxSourceValues>(
      'DROPBOX',
      () => ({ sourceUrl: DROPBOX_API }),
      (values) => ({ teamSpace: values.teamSpace }),
    ),
    Extra: DropboxTeamSwitch,
  },
  scopeRows: (values) => [
    ...folderRows(values),
    { label: 'Team-Bereich', value: values.teamSpace ? 'Wird gelesen' : 'Wird nicht gelesen' },
  ],
}
