import { useRef, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'
import { readGoogleDriveKey } from '@/features/connection'
import {
  GOOGLE_DRIVE_API,
  storedKeyServes,
  type GoogleDriveScope,
  type GoogleDriveSourceValues,
} from '../../utils/googleDriveSource'
import { connectionFields } from '../sources/sourceConnection'
import { fieldDomId, useSourceIssue } from '../sources/sourceIssueContext'
import type { SourceFormProps } from '../sources/types'
import { FieldGroup, SourceTextField } from '../sourceKit/fields'
import SelectionSummary from '../sourceKit/SelectionSummary'
import { driveItem, driveScopeOfItem, driveSelectionSource } from '../sourceKit/selectionSources'

/**
 * The Google Drive form (Variante E): Anmeldung with the service account's key file - a stored
 * one shows as „Gesetzt“ with „Ersetzen“ - and the imitated account; Umfang with the shared drives
 * and folders, chosen in the shared selection dialog.
 */
export default function DriveForm({
  values,
  onChange,
  context,
}: SourceFormProps<GoogleDriveSourceValues>) {
  const fields = connectionFields(context)
  const issueFor = useSourceIssue()
  const prefix = context.idPrefix
  const fileInput = useRef<HTMLInputElement | null>(null)
  const [keyError, setKeyError] = useState<string | null>(null)
  const profile = fields.connection
  const subjectOfProfile = profile ? profile.defaults.subject : undefined
  const keyId = fieldDomId(prefix, 'keyFile')
  const keyIssue = keyError ?? issueFor('keyFile')
  const stored = context.mode === 'edit' && storedKeyServes(values, context.credentialsStored)
  const keyServes = !fields.asksSecret || values.keyFile !== '' || stored

  async function onKeyFile(file: File | undefined) {
    if (!file) return
    const text = await file.text()
    const read = readGoogleDriveKey(text)
    if ('error' in read) {
      setKeyError(read.error)
      onChange({ keyFile: '', keyAccount: '' })
    } else {
      setKeyError(null)
      onChange({ keyFile: text, keyAccount: read.account })
    }
    if (fileInput.current) fileInput.current.value = ''
  }

  const source = driveSelectionSource(fields, () => ({
    sourceUrl: GOOGLE_DRIVE_API,
    sourceCredentials: values.keyFile || undefined,
    query: { subject: values.subject.trim() || undefined },
  }))

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      {(fields.asksSecret || !profile) && (
        <FieldGroup title="Anmeldung">
          {fields.asksSecret && (
            <Box>
              <FieldLabel id={`${keyId}-label`}>Schlüsseldatei</FieldLabel>
              <Box
                sx={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 1.5,
                  flexWrap: 'wrap',
                  minHeight: 40,
                }}
              >
                <Typography sx={{ fontSize: 14, overflowWrap: 'anywhere' }}>
                  {values.keyAccount !== ''
                    ? values.keyAccount
                    : stored
                      ? 'Gesetzt'
                      : 'Keine Datei gewählt'}
                </Typography>
                <Button
                  id={keyId}
                  variant="outlined"
                  size="small"
                  onClick={() => fileInput.current?.click()}
                  aria-describedby={keyIssue ? `${keyId}-error` : undefined}
                >
                  {values.keyAccount !== '' || stored ? 'Ersetzen' : 'Datei wählen'}
                </Button>
                <input
                  ref={fileInput}
                  type="file"
                  accept="application/json,.json"
                  hidden
                  aria-label="Schlüsseldatei des Dienstkontos"
                  onChange={(e) => void onKeyFile(e.target.files?.[0])}
                />
              </Box>
              {keyIssue ? (
                <Typography
                  id={`${keyId}-error`}
                  sx={{ fontSize: 12, color: 'error.main', mt: 0.5 }}
                >
                  {keyIssue}
                </Typography>
              ) : (
                <Typography sx={{ fontSize: 12, color: 'text.secondary', mt: 0.5 }}>
                  JSON-Schlüssel des Dienstkontos; wird nie zurückgegeben.
                </Typography>
              )}
            </Box>
          )}
          {!profile && (
            <SourceTextField
              idPrefix={prefix}
              field="subject"
              label="Imitiertes Konto"
              optional
              value={values.subject}
              onChange={(subject) => onChange({ subject })}
              placeholder="funktionskonto@example.org"
              hint="Nur mit domänenweiter Delegation."
            />
          )}
        </FieldGroup>
      )}
      {profile && (
        <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
          {typeof subjectOfProfile === 'string' && subjectOfProfile !== ''
            ? `Imitiertes Konto: ${subjectOfProfile} (vom Zugang „${profile.name}“)`
            : `Anmeldung über den Zugang „${profile.name}“.`}
        </Typography>
      )}
      <FieldGroup title="Umfang">
        <SelectionSummary
          id={fieldDomId(prefix, 'selection')}
          label="Ablagen und Ordner"
          source={source}
          value={values.scopes.map(driveItem)}
          onChange={(items) =>
            onChange({
              scopes: items
                .map(driveScopeOfItem)
                .filter((scope): scope is GoogleDriveScope => scope !== null),
            })
          }
          emptyText="Noch nichts ausgewählt."
          error={issueFor('selection')}
          notReady={keyServes ? null : 'Bitte zuerst die Schlüsseldatei wählen.'}
        />
      </FieldGroup>
    </Box>
  )
}
