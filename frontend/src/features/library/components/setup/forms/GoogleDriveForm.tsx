import { useCallback, useRef, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'
import { readGoogleDriveKey } from '@/features/connection'
import { browseSource, testLibrarySource } from '../../../api/libraryApi'
import {
  GOOGLE_DRIVE_API,
  MAX_GOOGLE_DRIVE_SCOPES,
  googleDriveScopeChecksOf,
  googleDriveScopeFromKey,
  googleDriveScopeKey,
  googleDriveScopeKindLabel,
  googleDriveScopeLabel,
  googleDriveSettingsOf,
  storedKeyServes,
  type GoogleDriveScope,
  type GoogleDriveSourceValues,
} from '../../../utils/googleDriveSource'
import { connectionFields } from '../../sources/sourceConnection'
import { useSourceIssue } from '../../sources/sourceIssueContext'
import type { SourceFormProps } from '../../sources/types'
import ConnectionStage from '../ConnectionStage'
import { outcomeOf, outcomeOfError, type ConnectionOutcome } from '../connectionOutcome'
import SourceField from '../SourceField'
import { pickedOfDriveScopes } from '../pickedOf'
import SplitPicker from '../SplitPicker'
import type { PickerLevel } from '../pickerTypes'
import { SelectionPending } from './AccountFolderForm'

/**
 * The source form of a Google Drive library: the service account's key file and an optional
 * account to act for in „Verbindung"; shared drives and shared folders in the selection, a folder
 * id typed by hand below it.
 */
export default function GoogleDriveForm({
  values,
  onChange,
  context,
}: SourceFormProps<GoogleDriveSourceValues>) {
  const id = `${context.idPrefix}-google-drive`
  const connection = connectionFields(context)
  const issueFor = useSourceIssue()
  const [outcome, setOutcome] = useState<ConnectionOutcome | null>(null)
  const [keyError, setKeyError] = useState<string | null>(null)
  const fileInput = useRef<HTMLInputElement | null>(null)
  const keyStored = context.mode === 'edit' && storedKeyServes(values, context.credentialsStored)
  const profileSubject = connection.connection?.defaults.subject
  const token = JSON.stringify({
    probe: connection.probe,
    key: values.keyFile,
    subject: values.subject,
  })
  const request = () => ({
    sourceUrl: GOOGLE_DRIVE_API,
    sourceCredentials: values.keyFile || undefined,
  })

  const run = async (): Promise<ConnectionOutcome> => {
    try {
      const response = await testLibrarySource(
        connection.probeRequest({
          sourceType: 'GOOGLE_DRIVE',
          ...request(),
          sourceSettings: { ...googleDriveSettingsOf(values), scopes: [] },
        }),
      )
      return outcomeOf(response, ['auth', 'rights'])
    } catch (err) {
      return outcomeOfError(err, ['auth', 'rights'])
    }
  }

  const load = useCallback(
    async (): Promise<PickerLevel> => {
      const response = await browseSource(
        'GOOGLE_DRIVE',
        connection.probeRequest({
          ...request(),
          query: { subject: values.subject.trim() || undefined },
        }),
      )
      return {
        complete: response.complete,
        message: response.message ?? null,
        entries: response.entries.flatMap((entry) => {
          const scope = googleDriveScopeFromKey(entry.key, entry.name ?? null)
          if (!scope) return []
          return [
            {
              key: googleDriveScopeKey(scope),
              name: googleDriveScopeLabel(scope),
              hint: googleDriveScopeKindLabel(scope.kind),
              selectable: true,
              hasChildren: false,
              raw: { kind: scope.kind, id: scope.id },
            },
          ]
        }),
      }
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [token],
  )

  const onKeyFile = async (file: File | undefined) => {
    if (!file) return
    const read = readGoogleDriveKey(await file.text())
    if ('error' in read) {
      setKeyError(read.error)
      onChange({ keyFile: '', keyAccount: '' })
    } else {
      setKeyError(null)
      onChange({ keyFile: await file.text(), keyAccount: read.account })
    }
    if (fileInput.current) fileInput.current.value = ''
  }

  const problems = Object.fromEntries(
    (googleDriveScopeChecksOf(outcome?.details) ?? [])
      .filter((check) => !check.reachable && check.message)
      .map((check) => [check.scope, String(check.message)]),
  )
  const keyIssue = keyError ?? issueFor('keyFile')

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <ConnectionStage
        idPrefix={id}
        token={token}
        stages={['auth', 'rights']}
        run={run}
        summary={[
          values.keyAccount ||
            (keyStored ? 'gespeicherter Schlüssel' : (connection.connection?.name ?? '')),
          values.subject.trim() ? `für ${values.subject.trim()}` : '',
        ]}
        fields={['keyFile', 'subject']}
        autoTest={context.mode === 'edit' || values.keyFile !== '' || !connection.asksSecret}
        onOutcome={setOutcome}
      >
        {connection.asksSecret && (
          <Box>
            <FieldLabel id={`${id}-key-label`}>Schlüsseldatei</FieldLabel>
            <Box
              sx={{
                display: 'flex',
                alignItems: 'center',
                gap: 1.5,
                flexWrap: 'wrap',
                minHeight: 40,
              }}
            >
              <Typography sx={{ fontSize: 14, overflowWrap: 'anywhere' }} id={`${id}-key-state`}>
                {values.keyAccount ? values.keyAccount : keyStored ? 'gesetzt' : 'keine gewählt'}
              </Typography>
              <Button
                variant="outlined"
                size="small"
                data-source-field="keyFile"
                onClick={() => fileInput.current?.click()}
                aria-describedby={`${id}-key-state${keyIssue ? ` ${id}-key-issue` : ''}`}
              >
                {values.keyAccount || keyStored ? 'Ersetzen' : 'Datei wählen'}
              </Button>
              <input
                ref={fileInput}
                type="file"
                accept="application/json,.json"
                hidden
                aria-labelledby={`${id}-key-label`}
                onChange={(e) => void onKeyFile(e.target.files?.[0])}
              />
            </Box>
            {keyIssue ? (
              <Typography
                id={`${id}-key-issue`}
                sx={{ fontSize: 12, color: 'error.main', mt: 0.5 }}
              >
                {keyIssue}
              </Typography>
            ) : (
              <Typography sx={{ fontSize: 12, color: 'text.secondary', mt: 0.5 }}>
                JSON-Schlüssel des Dienstkontos aus der Google Cloud Console.
              </Typography>
            )}
          </Box>
        )}
        {(!connection.connection || profileSubject !== undefined) && (
          <SourceField
            id={`${id}-subject`}
            field="subject"
            label="Im Namen von"
            optional
            value={values.subject}
            onChange={(next) => onChange({ subject: next })}
            placeholder="name@example.org"
            hint="Nur für „Meine Ablage“ eines Kontos der Domäne."
            readOnly={profileSubject !== undefined}
          />
        )}
      </ConnectionStage>

      {outcome?.ok ? (
        <SplitPicker
          key={token}
          idPrefix={`${id}-scopes`}
          title="Auswahl"
          rootLabel="Google Drive"
          noun={{ one: 'Bereich', many: 'Bereiche' }}
          load={load}
          selected={pickedOfDriveScopes(values.scopes)}
          onChange={(next) =>
            onChange({
              scopes: next.map((item) => ({
                kind: item.raw?.kind as GoogleDriveScope['kind'],
                id: String(item.raw?.id ?? ''),
                name: item.name,
              })),
            })
          }
          max={MAX_GOOGLE_DRIVE_SCOPES}
          emptySelection="Noch kein Bereich gewählt."
          issueFields={['scopes']}
          problems={problems}
          manualEntryLabel="Ordner-ID von Hand eintragen"
          manualEntry={
            <ManualFolder
              idPrefix={id}
              onAdd={(scope) => {
                if (
                  !values.scopes.some((s) => googleDriveScopeKey(s) === googleDriveScopeKey(scope))
                ) {
                  onChange({ scopes: [...values.scopes, scope] })
                }
              }}
            />
          }
        />
      ) : (
        <SelectionPending issueFields={['scopes']} />
      )}
    </Box>
  )
}

function ManualFolder({
  idPrefix,
  onAdd,
}: {
  idPrefix: string
  onAdd: (scope: GoogleDriveScope) => void
}) {
  const [text, setText] = useState('')
  const [error, setError] = useState<string | null>(null)
  return (
    <Box
      component="form"
      onSubmit={(e) => {
        e.preventDefault()
        const scope = googleDriveScopeFromKey(`folder:${text.trim()}`, null)
        if (!scope) {
          setError('Die Ordner-ID steht in der Adresse des Ordners hinter /folders/.')
          return
        }
        setError(null)
        onAdd(scope)
        setText('')
      }}
      sx={{ mt: 1 }}
    >
      <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1, alignItems: 'flex-end' }}>
        <Box sx={{ flex: '1 1 14rem' }}>
          <FieldLabel htmlFor={`${idPrefix}-folder-id`}>Ordner-ID von Hand</FieldLabel>
          <TextField
            id={`${idPrefix}-folder-id`}
            size="small"
            fullWidth
            value={text}
            error={error !== null}
            slotProps={{
              htmlInput: { 'aria-describedby': error ? `${idPrefix}-folder-id-error` : undefined },
            }}
            onChange={(e) => setText(e.target.value)}
            placeholder="1AbCdEfGhIjKlMnOp"
          />
        </Box>
        <Button type="submit" variant="outlined">
          Hinzufügen
        </Button>
      </Box>
      {error && (
        <Typography
          id={`${idPrefix}-folder-id-error`}
          sx={{ fontSize: 12, color: 'error.main', mt: 0.5 }}
        >
          {error}
        </Typography>
      )}
    </Box>
  )
}
