import { useRef, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { readGoogleDriveKey } from '@/features/connection'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'
import {
  GOOGLE_DRIVE_API,
  googleDrivePayloadOf,
  googleDriveScopeFromKey,
  googleDriveScopeKey,
  googleDriveScopeKindLabel,
  googleDriveScopeLabel,
  storedKeyServes,
  type GoogleDriveScope,
  type GoogleDriveSourceValues,
} from '../../utils/googleDriveSource'
import { connectionFields } from '../sources/sourceConnection'
import type { SourceFormProps } from '../sources/types'
import ConnectionCheck from './ConnectionCheck'
import FlatScope from './FlatScope'
import ScopeLoader from './ScopeLoader'
import SetupField, { ScopeField } from './SetupField'
import SourceSetupLayout from './SourceSetupLayout'
import { browseWith, testRequestOf, tokenOf } from './probe'
import { fieldId, issueMessage, useSetup } from './setupContext'

/**
 * Google Drive on the shared frame: the address is fixed, so Zugangsdaten (key file, imitated
 * account) come first; Umfang lists shared drives and folders, a folder ID can be added by hand.
 */
export default function GoogleDriveSetup({
  values,
  onChange,
  context,
}: SourceFormProps<GoogleDriveSourceValues>) {
  const fields = connectionFields(context)
  const { idPrefix, issues } = useSetup()
  const fileInput = useRef<HTMLInputElement | null>(null)
  const [keyError, setKeyError] = useState<string | null>(null)
  const [folderId, setFolderId] = useState('')
  const keyIssue = keyError ?? issueMessage(issues, 'keyFile')
  const keyServes =
    !fields.asksSecret ||
    values.keyFile !== '' ||
    storedKeyServes(values, context.credentialsStored)
  const subjectFixed = fields.isFixed('subject')
  const connectionToken = tokenOf(values.keyFile, values.subject.trim())

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

  function addFolder() {
    const scope = googleDriveScopeFromKey(`folder:${folderId.trim()}`, null)
    if (!scope) return
    if (!values.scopes.some((s) => googleDriveScopeKey(s) === googleDriveScopeKey(scope))) {
      onChange({ scopes: [...values.scopes, scope] })
    }
    setFolderId('')
  }

  const keyId = fieldId(idPrefix, 'keyFile')
  return (
    <SourceSetupLayout
      credentials={
        <>
          {fields.asksSecret && (
            <Box>
              <FieldLabel id={`${keyId}-label`}>Schlüsseldatei des Dienstkontos</FieldLabel>
              <Box sx={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 1.5 }}>
                <Button
                  id={keyId}
                  variant="outlined"
                  size="small"
                  onClick={() => fileInput.current?.click()}
                  aria-describedby={`${keyId}-status`}
                  sx={keyIssue ? { borderColor: 'error.main' } : undefined}
                >
                  {values.keyFile || (context.credentialsStored && !keyServes)
                    ? 'Andere Datei wählen'
                    : 'Datei wählen'}
                </Button>
                <Typography id={`${keyId}-status`} sx={{ fontSize: 13, overflowWrap: 'anywhere' }}>
                  {values.keyAccount
                    ? values.keyAccount
                    : storedKeyServes(values, context.credentialsStored)
                      ? 'gesetzt'
                      : 'Keine Datei gewählt'}
                </Typography>
              </Box>
              <input
                ref={fileInput}
                type="file"
                accept="application/json,.json"
                hidden
                aria-labelledby={`${keyId}-label`}
                onChange={(event) => void onKeyFile(event.target.files?.[0])}
              />
              <Typography
                sx={{ fontSize: 12, mt: 0.5, color: keyIssue ? 'error.main' : 'text.secondary' }}
              >
                {keyIssue ?? 'JSON-Datei aus der Google Cloud Console; sie wird nie angezeigt.'}
              </Typography>
            </Box>
          )}
          <SetupField
            field="subject"
            label="Imitiertes Konto (optional)"
            value={values.subject}
            onChange={(subject) => onChange({ subject })}
            placeholder="name@behoerde.de"
            hint={subjectFixed ? (fields.fixedHint ?? undefined) : 'Nötig für „Meine Ablage“.'}
            readOnly={subjectFixed}
            width="medium"
          />
        </>
      }
      check={
        <ConnectionCheck
          stages={['signIn', 'access']}
          token={tokenOf(connectionToken, values.scopes.map(googleDriveScopeKey))}
          missingHint="Bitte zuerst die Schlüsseldatei wählen."
          request={() => (keyServes ? testRequestOf(context, googleDrivePayloadOf(values)) : null)}
        />
      }
      scope={
        <ScopeField field="scopes">
          <ScopeLoader
            loadLabel="Bereiche laden"
            hint="Geteilte Ablagen und freigegebene Ordner, die das Dienstkonto sieht."
            token={connectionToken}
            autoLoad={context.mode === 'edit' && keyServes}
          >
            {() => (
              <FlatScope
                load={() =>
                  browseWith(context, {
                    sourceUrl: GOOGLE_DRIVE_API,
                    sourceCredentials: values.keyFile || undefined,
                    query: { subject: values.subject.trim() || undefined },
                  })
                }
                toItem={(entry) => {
                  const scope = googleDriveScopeFromKey(entry.key, entry.name ?? null)
                  return scope ? scopeItem(scope) : null
                }}
                selected={values.scopes.map(scopeItem)}
                onChange={(next) =>
                  onChange({
                    scopes: next
                      .map((item) => googleDriveScopeFromKey(item.id, item.label))
                      .filter((scope): scope is GoogleDriveScope => scope !== null),
                  })
                }
                label="Bereiche"
                noun={{ one: 'Bereich', many: 'Bereiche' }}
                emptyText="Das Dienstkonto sieht keine geteilten Ablagen oder Ordner."
              />
            )}
          </ScopeLoader>
          <Box sx={{ mt: 2.5 }}>
            <FieldLabel htmlFor={`${idPrefix}-folder-id`}>Ordner per ID hinzufügen</FieldLabel>
            <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap' }}>
              <TextField
                id={`${idPrefix}-folder-id`}
                size="small"
                value={folderId}
                onChange={(event) => setFolderId(event.target.value)}
                placeholder="1AbCdEf…"
                sx={{ flex: '1 1 200px', maxWidth: 360 }}
              />
              <Button variant="text" onClick={addFolder}>
                Hinzufügen
              </Button>
            </Box>
          </Box>
        </ScopeField>
      }
    />
  )
}

function scopeItem(scope: GoogleDriveScope) {
  return {
    id: googleDriveScopeKey(scope),
    label: googleDriveScopeLabel(scope),
    secondary: googleDriveScopeKindLabel(scope.kind),
  }
}
