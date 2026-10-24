import { useRef, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'
import { readGoogleDriveKey } from '@/features/connection'
import { browseSource } from '../../../api/libraryApi'
import {
  GOOGLE_DRIVE_API,
  googleDriveScopeFromKey,
  storedKeyServes,
  type GoogleDriveSourceValues,
} from '../../../utils/googleDriveSource'
import PickCombo from '../PickCombo'
import SetupTextField from '../SetupTextField'
import type { SetupFieldsProps } from '../setupTypes'
import { driveNode, driveScopeOf } from './driveNodes'

export function GoogleDriveConnectionFields({
  values,
  onChange,
  context,
  fields,
  issueAt,
  fieldId,
}: SetupFieldsProps<GoogleDriveSourceValues>) {
  const input = useRef<HTMLInputElement>(null)
  const [readError, setReadError] = useState<string | null>(null)
  const serves = storedKeyServes(values, context.credentialsStored)
  const status =
    values.keyAccount !== ''
      ? `Dienstkonto ${values.keyAccount}`
      : serves
        ? 'gesetzt'
        : 'Noch keine Datei gewählt'

  async function onFile(file: File | undefined) {
    if (!file) return
    const text = await file.text()
    const read = readGoogleDriveKey(text)
    if ('error' in read) {
      setReadError(read.error)
      onChange({ keyFile: '', keyAccount: '' })
    } else {
      setReadError(null)
      onChange({ keyFile: text, keyAccount: read.account })
    }
    if (input.current) input.current.value = ''
  }

  const error = readError ?? issueAt('keyFile')
  return (
    <Stack spacing={2}>
      {fields.asksSecret && (
        <Box>
          <FieldLabel htmlFor={fieldId('keyFile')}>Schlüsseldatei</FieldLabel>
          <Box sx={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 1.5, minHeight: 40 }}>
            <Typography sx={{ fontSize: 13.5, overflowWrap: 'anywhere' }}>{status}</Typography>
            <Button
              size="small"
              variant="outlined"
              id={fieldId('keyFile')}
              onClick={() => input.current?.click()}
              aria-describedby={error ? `${fieldId('keyFile')}-error` : undefined}
            >
              {serves || values.keyAccount ? 'Ersetzen' : 'Datei wählen'}
            </Button>
            <input
              ref={input}
              type="file"
              accept="application/json,.json"
              hidden
              aria-label="Schlüsseldatei des Dienstkontos"
              onChange={(e) => void onFile(e.target.files?.[0])}
            />
          </Box>
          <Typography
            id={`${fieldId('keyFile')}-error`}
            sx={{ fontSize: 12, mt: 0.5, color: error ? 'error.main' : 'text.secondary' }}
          >
            {error ?? 'Die JSON-Datei des Dienstkontos aus der Google Cloud Console.'}
          </Typography>
        </Box>
      )}
      <SetupTextField
        id={fieldId('subject')}
        label="Imitiertes Konto"
        optional
        value={values.subject}
        onChange={(subject) => onChange({ subject })}
        error={issueAt('subject')}
        hint="Nur für „Meine Ablage“ einer Person; braucht domänenweite Delegierung."
        placeholder="person@rheinfurt.example"
      />
    </Stack>
  )
}

export function GoogleDriveScopeFields({
  values,
  onChange,
  fields,
  issueAt,
  fieldId,
}: SetupFieldsProps<GoogleDriveSourceValues>) {
  const request = { sourceUrl: GOOGLE_DRIVE_API, sourceCredentials: values.keyFile || undefined }
  const subject = values.subject.trim() || undefined
  return (
    <PickCombo
      id={fieldId('scopes')}
      label="Bereiche"
      hint="Geteilte Ablagen und für das Dienstkonto freigegebene Ordner. Einen anderen Ordner nehmen Sie über seine ID auf."
      error={issueAt('scopes')}
      noun={['Bereich', 'Bereiche']}
      selected={values.scopes.map(driveNode)}
      onChange={(nodes) => onChange({ scopes: nodes.map(driveScopeOf) })}
      load={async () => {
        const result = await browseSource(
          'GOOGLE_DRIVE',
          fields.probeRequest({ ...request, query: { subject } }),
        )
        return {
          complete: result.complete,
          message: result.message,
          nodes: result.entries
            .map((entry) => googleDriveScopeFromKey(entry.key, entry.name ?? null))
            .filter((scope) => scope !== null)
            .map(driveNode),
        }
      }}
      max={50}
      manual={(text) => {
        const scope = googleDriveScopeFromKey(`folder:${text.trim()}`, null)
        return scope && text.trim().length >= 10 ? driveNode(scope) : null
      }}
      manualLabel={(text) => `Ordner-ID „${text.trim()}“ übernehmen`}
      loadKey={JSON.stringify({ request, subject, probe: fields.probe })}
      placeholder="Bereich suchen oder Ordner-ID einfügen"
    />
  )
}
