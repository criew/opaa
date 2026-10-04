import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type { SourceBrowseEntry } from '../../types/api'
import { browseSource, testLibrarySource } from '../../services/libraryApi'
import {
  sameSmbServer,
  smbCredentialsOf,
  smbFoldersOf,
  type SmbSourceValues,
} from '../../utils/smbSource'
import FieldLabel from '../wizard/FieldLabel'

interface SmbSourceFormProps {
  mode: 'create' | 'edit'
  idPrefix: string
  /** Edit mode: lets test and folder listing fall back to the stored credentials. */
  libraryId?: string
  credentialsStored: boolean
  originalSourceUrl?: string | null
  values: SmbSourceValues
  onChange: (patch: Partial<SmbSourceValues>) => void
}

interface Message {
  severity: 'success' | 'warning' | 'error'
  text: string
}

/**
 * The source form of a Windows file share: share address, service account with password and the
 * folders, one per line. „Ordner laden" offers the folders in the share's root as chips that add a
 * line; „Verbindung testen" signs in, opens the share and checks every folder. Each result carries
 * the values it was measured with and is shown only while they are unchanged - a changed address,
 * account, password or (for the test) folder hides it, an answer arriving after a change included.
 */
export default function SmbSourceForm({
  mode,
  idPrefix,
  libraryId,
  credentialsStored,
  originalSourceUrl,
  values,
  onChange,
}: SmbSourceFormProps) {
  const isCreate = mode === 'create'
  const [test, setTest] = useState<{ token: string; message: Message } | null>(null)
  const [listing, setListing] = useState<{
    token: string
    options: SourceBrowseEntry[] | null
    message: Message | null
  } | null>(null)
  const [testing, setTesting] = useState(false)
  const [loading, setLoading] = useState(false)

  const connectionToken = JSON.stringify({
    libraryId,
    sourceUrl: values.sourceUrl,
    account: values.account,
    password: values.password,
  })
  const testToken = JSON.stringify({ connectionToken, folders: values.folders })
  const visibleTest = test?.token === testToken ? test.message : null
  const visibleListing = listing?.token === connectionToken ? listing : null
  const folderOptions = visibleListing?.options ?? null
  const folderMessage = visibleListing?.message ?? null

  const addressEntered = values.sourceUrl.trim() !== ''
  const credentialsKept = credentialsStored && sameSmbServer(originalSourceUrl, values.sourceUrl)
  const credentialsHint = isCreate
    ? 'Das Passwort des Dienstkontos. Wird nie ausgegeben.'
    : !credentialsStored
      ? 'Für diese Bibliothek sind keine Zugangsdaten gespeichert.'
      : credentialsKept || !addressEntered
        ? 'Leer lassen, um die gespeicherten Zugangsdaten beizubehalten.'
        : 'Die Adresse zeigt auf einen anderen Server - bitte die Zugangsdaten neu eingeben.'

  function connectionPayload() {
    return {
      sourceUrl: values.sourceUrl.trim(),
      sourceInsecureSsl: false,
      sourceCredentials: smbCredentialsOf(values),
      libraryId: mode === 'edit' ? libraryId : undefined,
    }
  }

  async function handleTest() {
    const token = testToken
    setTesting(true)
    try {
      const result = await testLibrarySource({
        sourceType: 'SMB',
        ...connectionPayload(),
        sourceSettings: { folders: smbFoldersOf(values) },
      })
      setTest({
        token,
        message: { severity: result.reachable ? 'success' : 'warning', text: result.message },
      })
    } catch (err) {
      setTest({
        token,
        message: {
          severity: 'error',
          text: err instanceof Error ? err.message : 'Verbindung konnte nicht getestet werden',
        },
      })
    } finally {
      setTesting(false)
    }
  }

  async function handleLoadFolders() {
    const token = connectionToken
    setLoading(true)
    try {
      const result = await browseSource('SMB', connectionPayload())
      setListing({
        token,
        options: result.entries,
        message: !result.complete
          ? { severity: 'warning', text: result.message ?? 'Die Ordner sind nicht auflistbar.' }
          : result.entries.length === 0
            ? { severity: 'warning', text: 'Im Stamm der Freigabe liegen keine Ordner.' }
            : null,
      })
    } catch (err) {
      setListing({
        token,
        options: null,
        message: {
          severity: 'error',
          text: err instanceof Error ? err.message : 'Ordner konnten nicht geladen werden',
        },
      })
    } finally {
      setLoading(false)
    }
  }

  function addFolder(folder: string) {
    const current = smbFoldersOf(values).filter((entry) => entry !== '/')
    if (!current.includes(folder)) {
      onChange({ folders: [...current, folder].join('\n') })
    }
  }

  return (
    <Box>
      {isCreate && (
        <Typography component="h3" sx={{ fontSize: 16, fontWeight: 600, mb: 1.75 }}>
          Verbindung zur Dateifreigabe
        </Typography>
      )}
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: isCreate ? { xs: '1fr', sm: '1fr 1fr' } : '1fr',
          gap: '14px',
        }}
      >
        <Box sx={{ gridColumn: '1 / -1' }}>
          <FieldLabel htmlFor={`${idPrefix}-url`}>Adresse der Freigabe</FieldLabel>
          <TextField
            id={`${idPrefix}-url`}
            size="small"
            fullWidth
            value={values.sourceUrl}
            onChange={(e) => onChange({ sourceUrl: e.target.value })}
            placeholder="smb://dateiserver.example.org/Freigabe"
            helperText="Server und Freigabe; auch ein UNC-Pfad wie \\dateiserver\Freigabe wird angenommen."
            slotProps={{ htmlInput: { maxLength: 2000, sx: { fontFamily: 'monospace' } } }}
          />
        </Box>
        <Box>
          <FieldLabel htmlFor={`${idPrefix}-account`}>Dienstkonto</FieldLabel>
          <TextField
            id={`${idPrefix}-account`}
            size="small"
            fullWidth
            value={values.account}
            onChange={(e) => onChange({ account: e.target.value })}
            placeholder="DOMÄNE\svc-opaa"
            helperText="DOMÄNE\Benutzer, Benutzer@domäne oder ein lokales Konto."
            autoComplete="off"
            slotProps={{ htmlInput: { maxLength: 255 } }}
          />
        </Box>
        <Box>
          <FieldLabel htmlFor={`${idPrefix}-password`}>Passwort</FieldLabel>
          <TextField
            id={`${idPrefix}-password`}
            size="small"
            type="password"
            fullWidth
            value={values.password}
            onChange={(e) => onChange({ password: e.target.value })}
            helperText={credentialsHint}
            autoComplete="new-password"
            slotProps={{ htmlInput: { maxLength: 3800 } }}
          />
        </Box>
        <Box sx={{ gridColumn: '1 / -1' }}>
          <FieldLabel htmlFor={`${idPrefix}-folders`}>Ordner</FieldLabel>
          <TextField
            id={`${idPrefix}-folders`}
            size="small"
            fullWidth
            multiline
            minRows={2}
            value={values.folders}
            onChange={(e) => onChange({ folders: e.target.value })}
            helperText="Ein Ordner je Zeile, ausgehend von der Freigabe, etwa /Projekte. / liest die ganze Freigabe."
            slotProps={{ htmlInput: { sx: { fontFamily: 'monospace' } } }}
          />
          {folderOptions && folderOptions.length > 0 && (
            <Stack
              direction="row"
              spacing={0.5}
              useFlexGap
              sx={{ flexWrap: 'wrap', mt: 1 }}
              aria-label="Gefundene Ordner"
            >
              {folderOptions.map((entry) => (
                <Chip
                  key={entry.key}
                  size="small"
                  label={entry.name ?? entry.key}
                  onClick={() => addFolder(entry.key)}
                  aria-label={`Ordner ${entry.key} übernehmen`}
                />
              ))}
            </Stack>
          )}
          {folderMessage && (
            <Alert severity={folderMessage.severity} sx={{ mt: 1 }}>
              {folderMessage.text}
            </Alert>
          )}
        </Box>
        <Stack direction="row" spacing={1} sx={{ gridColumn: '1 / -1' }}>
          <Button variant="outlined" onClick={() => void handleLoadFolders()} disabled={loading}>
            {loading ? 'Ordner werden geladen …' : 'Ordner laden'}
          </Button>
          <Button variant="outlined" onClick={() => void handleTest()} disabled={testing}>
            {testing ? 'Wird geprüft …' : 'Verbindung testen'}
          </Button>
        </Stack>
        {visibleTest && (
          <Alert severity={visibleTest.severity} sx={{ gridColumn: '1 / -1' }}>
            {visibleTest.text}
          </Alert>
        )}
      </Box>
    </Box>
  )
}
