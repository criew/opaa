import { useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import FormControlLabel from '@mui/material/FormControlLabel'
import IconButton from '@mui/material/IconButton'
import List from '@mui/material/List'
import ListItem from '@mui/material/ListItem'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import DeleteIcon from '@mui/icons-material/Delete'
import UploadFileIcon from '@mui/icons-material/UploadFile'
import { browseSource, testLibrarySource } from '../../services/libraryApi'
import {
  GOOGLE_DRIVE_API,
  googleDriveScopeChecksOf,
  googleDriveScopeFromKey,
  googleDriveScopeKey,
  googleDriveScopeKindLabel,
  googleDriveScopeLabel,
  googleDriveSettingsOf,
  MAX_GOOGLE_DRIVE_SCOPES,
  readGoogleDriveKey,
  storedKeyServes,
  type GoogleDriveScope,
  type GoogleDriveScopeCheck,
  type GoogleDriveSourceValues,
} from '../../utils/googleDriveSource'
import FieldLabel from '../wizard/FieldLabel'
import ScopeConsequence from './sources/ScopeConsequence'
import { connectionFields, type ConnectionFields } from './sources/sourceConnection'

interface Message {
  severity: 'success' | 'warning' | 'error' | 'info'
  text: string
}

interface GoogleDriveSourceFormProps {
  mode: 'create' | 'edit'
  values: GoogleDriveSourceValues
  onChange: (patch: Partial<GoogleDriveSourceValues>) => void
  /** Edit mode: lets the test and the listing fall back to the stored key. */
  libraryId?: string
  /** Edit mode: whether a key is stored for this library. */
  credentialsStored?: boolean
  idPrefix: string
  /** What the connection profile decides for the fields; nothing without one. */
  connection?: ConnectionFields
}

/**
 * The Google Drive form (ADR-0040): the service account's key file, read in the browser only to
 * show the account it belongs to, the optional imitated account, the scopes - chosen from what the
 * account sees or added by folder id - and the connection test per scope. The key travels once, in
 * the request; no response carries it back.
 */
export default function GoogleDriveSourceForm({
  mode,
  values,
  onChange,
  libraryId,
  credentialsStored = false,
  idPrefix,
  connection = connectionFields({
    mode,
    sourceType: 'GOOGLE_DRIVE',
    idPrefix,
    libraryId,
    credentialsStored,
  }),
}: GoogleDriveSourceFormProps) {
  const [keyError, setKeyError] = useState<string | null>(null)
  const [options, setOptions] = useState<GoogleDriveScope[]>([])
  const [listing, setListing] = useState(false)
  const [listingMessage, setListingMessage] = useState<Message | null>(null)
  const [testing, setTesting] = useState(false)
  const [testMessage, setTestMessage] = useState<Message | null>(null)
  const [checks, setChecks] = useState<GoogleDriveScopeCheck[] | null>(null)
  const [folderId, setFolderId] = useState('')
  // two counters: an edit of a scope must not discard a listing that is still in flight
  const testGeneration = useRef(0)
  const listingGeneration = useRef(0)
  const fileInput = useRef<HTMLInputElement | null>(null)

  const keyServes =
    !connection.asksSecret || values.keyFile !== '' || storedKeyServes(values, credentialsStored)
  const subjectChangedWithoutKey =
    mode === 'edit' && credentialsStored && values.keyFile === '' && !keyServes

  const change = (patch: Partial<GoogleDriveSourceValues>) => {
    testGeneration.current++
    setTesting(false)
    setTestMessage(null)
    setChecks(null)
    // a listing belongs to the key, the account and the route it was made with
    if ('keyFile' in patch || 'subject' in patch || 'sourceProxy' in patch) {
      listingGeneration.current++
      setListing(false)
      setOptions([])
      setListingMessage(null)
    }
    onChange(patch)
  }

  const connectionRequest = () => ({
    sourceUrl: GOOGLE_DRIVE_API,
    sourceProxy: values.sourceProxy.trim() || undefined,
    sourceCredentials: values.keyFile || undefined,
    sourceInsecureSsl: false,
    ...connection.probe,
  })

  const onKeyFile = async (file: File | undefined) => {
    if (!file) return
    const text = await file.text()
    const read = readGoogleDriveKey(text)
    if ('error' in read) {
      setKeyError(read.error)
      change({ keyFile: '', keyAccount: '' })
    } else {
      setKeyError(null)
      change({ keyFile: text, keyAccount: read.account })
    }
    if (fileInput.current) fileInput.current.value = ''
  }

  const selected = new Set(values.scopes.map(googleDriveScopeKey))

  const toggle = (scope: GoogleDriveScope) => {
    const key = googleDriveScopeKey(scope)
    change({
      scopes: selected.has(key)
        ? values.scopes.filter((s) => googleDriveScopeKey(s) !== key)
        : [...values.scopes, scope],
    })
  }

  const loadScopes = async () => {
    const mine = ++listingGeneration.current
    setListing(true)
    setListingMessage(null)
    try {
      const result = await browseSource('GOOGLE_DRIVE', {
        ...connectionRequest(),
        query: { subject: values.subject.trim() || undefined },
      })
      if (listingGeneration.current !== mine) return
      if (result.complete) {
        const found = result.entries
          .map((entry) => googleDriveScopeFromKey(entry.key, entry.name ?? null))
          .filter((scope): scope is GoogleDriveScope => scope !== null)
        setOptions(found)
        setListingMessage({
          severity: 'success',
          text:
            found.length === 0
              ? 'Das Konto sieht keine geteilten Ablagen und keine freigegebenen Ordner. Eine Ordner-ID lässt sich unten von Hand eintragen.'
              : `${found.length} Bereiche gefunden.`,
        })
      } else {
        setListingMessage({ severity: 'warning', text: result.message ?? 'Keine Auflistung.' })
      }
    } catch (err) {
      if (listingGeneration.current !== mine) return
      setListingMessage({
        severity: 'error',
        text: err instanceof Error ? err.message : 'Die Auflistung ist fehlgeschlagen.',
      })
    } finally {
      if (listingGeneration.current === mine) setListing(false)
    }
  }

  const addFolder = () => {
    const scope = googleDriveScopeFromKey(`folder:${folderId.trim()}`, null)
    if (!scope) {
      setListingMessage({
        severity: 'error',
        text: 'Eine Ordner-ID besteht aus Buchstaben, Ziffern, „-“ und „_“; sie steht in der Adresse des Ordners hinter /folders/.',
      })
      return
    }
    if (!selected.has(googleDriveScopeKey(scope))) {
      change({ scopes: [...values.scopes, scope] })
    }
    setFolderId('')
  }

  const runTest = async () => {
    const mine = ++testGeneration.current
    setTesting(true)
    setTestMessage(null)
    setChecks(null)
    try {
      const result = await testLibrarySource({
        sourceType: 'GOOGLE_DRIVE',
        ...connectionRequest(),
        sourceSettings: googleDriveSettingsOf(values),
      })
      if (testGeneration.current !== mine) return
      setTestMessage({ severity: result.reachable ? 'success' : 'warning', text: result.message })
      setChecks(googleDriveScopeChecksOf(result.details))
    } catch (err) {
      if (testGeneration.current !== mine) return
      setTestMessage({
        severity: 'error',
        text: err instanceof Error ? err.message : 'Der Verbindungstest ist fehlgeschlagen.',
      })
    } finally {
      if (testGeneration.current === mine) setTesting(false)
    }
  }

  const keyStatus =
    values.keyAccount !== ''
      ? `Schlüssel des Dienstkontos ${values.keyAccount} ausgewählt.`
      : mode === 'edit' && credentialsStored
        ? 'Ein Schlüssel ist gespeichert. Eine Datei nur für einen neuen Schlüssel wählen.'
        : 'Noch kein Schlüssel gewählt.'

  return (
    <Stack spacing={2.5}>
      {connection.asksSecret && (
        <Box>
          <FieldLabel htmlFor={`${idPrefix}-key`}>Dienstkonto-Schlüssel (JSON)</FieldLabel>
          <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
            <Button
              variant="outlined"
              component="label"
              startIcon={<UploadFileIcon />}
              data-testid="google-drive-key-button"
            >
              Schlüsseldatei wählen
              <input
                id={`${idPrefix}-key`}
                ref={fileInput}
                type="file"
                accept="application/json,.json"
                hidden
                aria-label="Schlüsseldatei des Dienstkontos"
                onChange={(e) => void onKeyFile(e.target.files?.[0])}
              />
            </Button>
            <Typography variant="body2" data-testid="google-drive-key-status">
              {keyStatus}
            </Typography>
          </Stack>
          {keyError && (
            <Alert severity="error" sx={{ mt: 1 }}>
              {keyError}
            </Alert>
          )}
          <Typography variant="caption" sx={{ color: 'text.secondary', display: 'block', mt: 0.5 }}>
            Die Datei verlässt den Browser nur mit dem Speichern oder dem Verbindungstest und ist
            danach in keiner Antwort mehr enthalten.
          </Typography>
        </Box>
      )}

      <Box>
        <FieldLabel htmlFor={`${idPrefix}-subject`}>Imitiertes Konto (optional)</FieldLabel>
        <TextField
          id={`${idPrefix}-subject`}
          size="small"
          fullWidth
          value={values.subject}
          onChange={(e) => change({ subject: e.target.value })}
          placeholder="funktionskonto@example.org"
          autoComplete="off"
          helperText={
            connection.isFixed('subject')
              ? connection.fixedHint
              : 'Nur mit domänenweiter Delegation. Bitte ein Funktionskonto, kein persönliches.'
          }
          slotProps={{ htmlInput: { readOnly: connection.isFixed('subject') } }}
        />
        {subjectChangedWithoutKey && (
          <Alert severity="info" sx={{ mt: 1 }} data-testid="google-drive-subject-needs-key">
            Für ein anderes imitiertes Konto muss die Schlüsseldatei neu hochgeladen werden.
          </Alert>
        )}
      </Box>

      <Box>
        <Typography variant="subtitle2" component="h3" sx={{ mb: 1 }}>
          Bereiche ({values.scopes.length} von höchstens {MAX_GOOGLE_DRIVE_SCOPES})
        </Typography>
        <ScopeConsequence testId="google-drive-sharing-consequence">
          Alles, was aus diesen Bereichen indiziert wird, ist für alle Leseberechtigten dieser
          Bibliothek sichtbar, unabhängig von den Freigaben in Google Drive.
        </ScopeConsequence>
        <Stack direction="row" spacing={1} sx={{ mt: 1.5 }}>
          <Button
            variant="outlined"
            onClick={() => void loadScopes()}
            disabled={listing || !keyServes}
          >
            {listing ? 'Lädt …' : 'Bereiche laden'}
          </Button>
        </Stack>
        {listingMessage && (
          <Alert severity={listingMessage.severity} sx={{ mt: 1 }}>
            {listingMessage.text}
          </Alert>
        )}
        {options.length > 0 && (
          <List dense aria-label="Gefundene Bereiche">
            {options.map((scope) => (
              <ListItem key={googleDriveScopeKey(scope)} disableGutters>
                <FormControlLabel
                  control={
                    <Checkbox
                      checked={selected.has(googleDriveScopeKey(scope))}
                      onChange={() => toggle(scope)}
                    />
                  }
                  label={`${googleDriveScopeLabel(scope)} (${googleDriveScopeKindLabel(scope.kind)})`}
                />
              </ListItem>
            ))}
          </List>
        )}
        <Stack direction="row" spacing={1} sx={{ mt: 1.5, alignItems: 'flex-start' }}>
          <TextField
            id={`${idPrefix}-folder`}
            size="small"
            label="Ordner-ID"
            value={folderId}
            onChange={(e) => setFolderId(e.target.value)}
            sx={{ flex: 1 }}
          />
          <Button variant="text" onClick={addFolder} disabled={folderId.trim() === ''}>
            Ordner hinzufügen
          </Button>
        </Stack>
        {values.scopes.length > 0 && (
          <List dense aria-label="Gewählte Bereiche">
            {values.scopes.map((scope) => {
              const key = googleDriveScopeKey(scope)
              const check = checks?.find((c) => c.scope === key)
              return (
                <ListItem
                  key={key}
                  disableGutters
                  secondaryAction={
                    <IconButton
                      edge="end"
                      aria-label={`Bereich ${googleDriveScopeLabel(scope)} entfernen`}
                      onClick={() => toggle(scope)}
                    >
                      <DeleteIcon />
                    </IconButton>
                  }
                >
                  <Typography variant="body2">
                    {googleDriveScopeLabel(scope)} · {googleDriveScopeKindLabel(scope.kind)}
                    {check &&
                      (check.reachable
                        ? ' · erreichbar'
                        : ` · ${check.message ?? 'nicht erreichbar'}`)}
                  </Typography>
                </ListItem>
              )
            })}
          </List>
        )}
      </Box>

      <Box>
        <FieldLabel htmlFor={`${idPrefix}-proxy`}>Proxy (optional)</FieldLabel>
        <TextField
          id={`${idPrefix}-proxy`}
          size="small"
          fullWidth
          value={values.sourceProxy}
          onChange={(e) => change({ sourceProxy: e.target.value })}
          placeholder="proxy.example.org:8080"
        />
      </Box>

      <Box>
        <Button
          variant="contained"
          onClick={() => void runTest()}
          disabled={testing || !keyServes || values.scopes.length === 0}
        >
          {testing ? 'Prüft …' : 'Verbindung testen'}
        </Button>
        {/* always in the DOM, so a screen reader announces the text that appears in it */}
        <Box role="status" aria-live="polite" data-testid="google-drive-test-status">
          {testMessage && (
            <Alert severity={testMessage.severity} sx={{ mt: 1 }} role="none">
              {testMessage.text}
              {checks?.some((check) => !check.reachable) && (
                <>
                  {' '}
                  {checks
                    .filter((check) => !check.reachable)
                    .map((check) => `${check.scope}: ${check.message ?? 'nicht erreichbar'}`)
                    .join('; ')}
                </>
              )}
            </Alert>
          )}
        </Box>
      </Box>
    </Stack>
  )
}
