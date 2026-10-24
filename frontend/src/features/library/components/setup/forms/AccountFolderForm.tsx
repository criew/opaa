import { useCallback, useContext, useState } from 'react'
import { SourceIssueContext, issueList } from '../../sources/sourceIssueContext'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import type { SourceTypeKey } from '@/shared/api/types'
import { testLibrarySource } from '../../../api/libraryApi'
import { connectionFields } from '../../sources/sourceConnection'
import type { SourceFormContext } from '../../sources/types'
import ConnectionStage from '../ConnectionStage'
import { outcomeOf, outcomeOfError, type ConnectionOutcome } from '../connectionOutcome'
import SecretField from '../SecretField'
import SourceField from '../SourceField'
import SplitPicker, { SelectionIssues } from '../SplitPicker'
import { folderLoader, foldersOfPicked, pickedOfFolders } from '../pathSelection'

export interface AccountFolderValues {
  sourceUrl: string
  folders: string
}

interface AccountFolderFormProps<V extends AccountFolderValues> {
  sourceType: SourceTypeKey
  values: V
  onChange: (patch: Partial<V>) => void
  context: SourceFormContext
  accountKey: keyof V & string
  secretKey: keyof V & string
  secretLabel: string
  addressPlaceholder: string
  addressHint: string
  /** The top level in breadcrumb and paths („Nextcloud", „Freigabe"). */
  rootLabel: string
  credentialsOf: (values: V) => string | undefined
  foldersOf: (values: V) => string[]
  /** Whether the stored secret still serves the entered address. */
  keepsSecret: (values: V) => boolean
  max: number
}

/**
 * The source form of a server with an account and a password and folders below a root (Nextcloud,
 * Windows-Dateifreigabe): „Verbindung" with address, account and password, then the folders in
 * the split selection. Nothing chosen reads the whole source.
 */
export default function AccountFolderForm<V extends AccountFolderValues>({
  sourceType,
  values,
  onChange,
  context,
  accountKey,
  secretKey,
  secretLabel,
  addressPlaceholder,
  addressHint,
  rootLabel,
  credentialsOf,
  foldersOf,
  keepsSecret,
  max,
}: AccountFolderFormProps<V>) {
  const id = `${context.idPrefix}-${sourceType.toLowerCase()}`
  const connection = connectionFields(context)
  const [outcome, setOutcome] = useState<ConnectionOutcome | null>(null)
  const account = String(values[accountKey] ?? '')
  const secret = String(values[secretKey] ?? '')
  const stored = context.mode === 'edit' && context.credentialsStored && keepsSecret(values)
  const token = JSON.stringify({ probe: connection.probe, url: values.sourceUrl, account, secret })
  const payload = useCallback(
    () => ({ sourceUrl: values.sourceUrl.trim(), sourceCredentials: credentialsOf(values) }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [token],
  )

  const run = async (): Promise<ConnectionOutcome> => {
    const stages = ['address', 'auth', 'rights'] as const
    try {
      const response = await testLibrarySource(
        connection.probeRequest({ sourceType, ...payload(), sourceSettings: { folders: ['/'] } }),
      )
      return outcomeOf(response, [...stages])
    } catch (err) {
      return outcomeOfError(err, [...stages])
    }
  }

  const load = useCallback(
    (trail: Parameters<ReturnType<typeof folderLoader>>[0]) =>
      folderLoader(sourceType, (query) => connection.probeRequest({ ...payload(), query }))(trail),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [token],
  )

  const complete =
    values.sourceUrl.trim() !== '' && (account.trim() !== '' || stored || !connection.asksSecret)

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <ConnectionStage
        idPrefix={id}
        token={token}
        stages={['address', 'auth', 'rights']}
        run={run}
        summary={[values.sourceUrl.trim(), account.trim() || (stored ? 'gespeichertes Konto' : '')]}
        fields={['sourceUrl', accountKey, secretKey]}
        autoTest={complete && (context.mode === 'edit' || secret !== '' || !connection.asksSecret)}
        onOutcome={setOutcome}
      >
        <SourceField
          id={`${id}-url`}
          field="sourceUrl"
          label="Adresse"
          value={values.sourceUrl}
          onChange={(next) => onChange({ sourceUrl: next } as Partial<V>)}
          placeholder={addressPlaceholder}
          hint={connection.addressHint ?? addressHint}
          mono
        />
        {connection.asksSecret && (
          <>
            <SourceField
              id={`${id}-account`}
              field={accountKey}
              label="Konto"
              value={account}
              onChange={(next) => onChange({ [accountKey]: next } as Partial<V>)}
              hint={stored && account === '' ? 'Leer lassen, um das gespeicherte Konto zu behalten.' : undefined}
            />
            <SecretField
              id={`${id}-secret`}
              field={secretKey}
              label={secretLabel}
              value={secret}
              onChange={(next) => onChange({ [secretKey]: next } as Partial<V>)}
              stored={stored}
            />
          </>
        )}
      </ConnectionStage>

      {outcome?.ok ? (
        <SplitPicker
          key={token}
          idPrefix={`${id}-folders`}
          title="Auswahl"
          rootLabel={rootLabel}
          noun={{ one: 'Ordner', many: 'Ordner' }}
          load={load}
          selected={pickedOfFolders(foldersOf(values))}
          onChange={(next) => onChange({ folders: foldersOfPicked(next) } as Partial<V>)}
          max={max}
          emptySelection={`Nichts gewählt: Gelesen wird die ganze ${rootLabel}.`}
          issueFields={['folders']}
        />
      ) : (
        <SelectionPending issueFields={['folders']} />
      )}
    </Box>
  )
}

/** The selection's place before the connection was tested. */
export function SelectionPending({
  title = 'Auswahl',
  issueFields = [],
}: {
  title?: string
  issueFields?: string[]
}) {
  const issues = issueList(useContext(SourceIssueContext)).filter(
    (issue) => issue.field !== undefined && issueFields.includes(issue.field),
  )
  return (
    <Box data-source-field="selection" tabIndex={-1} sx={{ outline: 'none' }}>
      <Typography component="h3" sx={{ fontSize: 16, fontWeight: 600, mb: 0.5 }}>
        {title}
      </Typography>
      {issues.length > 0 && <SelectionIssues messages={issues.map((issue) => issue.message)} />}
      <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
        Nach dem Verbindungstest wählen Sie hier aus, was gelesen wird.
      </Typography>
    </Box>
  )
}
