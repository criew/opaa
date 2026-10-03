import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import FormControlLabel from '@mui/material/FormControlLabel'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type { SourceBrowseEntry } from '../../types/api'
import { browseSource, testLibrarySource } from '../../services/libraryApi'
import { sameLibrarySourceOrigin } from '../../utils/librarySourceConfig'
import {
  nextcloudCredentialsOf,
  nextcloudFoldersOf,
  type NextcloudSourceValues,
} from '../../utils/nextcloudSource'
import FieldLabel from '../wizard/FieldLabel'

interface NextcloudSourceFormProps {
  mode: 'create' | 'edit'
  idPrefix: string
  /** Edit mode: lets test and folder listing fall back to the stored credentials. */
  libraryId?: string
  credentialsStored: boolean
  originalSourceUrl?: string | null
  values: NextcloudSourceValues
  onChange: (patch: Partial<NextcloudSourceValues>) => void
}

interface Message {
  severity: 'success' | 'warning' | 'error'
  text: string
}

/**
 * The source form of a Nextcloud library: address, technical user with app password, proxy,
 * certificate switch and the folders, one per line. „Ordner laden" offers the folders in the
 * user's root (own ones, shares, group folders) as chips that add a line; „Verbindung testen"
 * signs in and checks every folder. Each result carries the values it was measured with and is
 * shown only while they are unchanged - a changed address, user, password, proxy, switch or (for
 * the test) folder hides it, an answer arriving after a change included.
 */
export default function NextcloudSourceForm({
  mode,
  idPrefix,
  libraryId,
  credentialsStored,
  originalSourceUrl,
  values,
  onChange,
}: NextcloudSourceFormProps) {
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
    username: values.username,
    appPassword: values.appPassword,
    sourceProxy: values.sourceProxy,
    sourceInsecureSsl: values.sourceInsecureSsl,
  })
  const testToken = JSON.stringify({ connectionToken, folders: values.folders })
  const visibleTest = test?.token === testToken ? test.message : null
  const visibleListing = listing?.token === connectionToken ? listing : null
  const folderOptions = visibleListing?.options ?? null
  const folderMessage = visibleListing?.message ?? null

  const addressEntered = values.sourceUrl.trim() !== ''
  const credentialsKept =
    credentialsStored && sameLibrarySourceOrigin(originalSourceUrl, values.sourceUrl)
  const credentialsHint = isCreate
    ? 'Ein App-Passwort des technischen Nutzers (Einstellungen › Sicherheit). Wird nie ausgegeben.'
    : !credentialsStored
      ? 'Für diese Bibliothek sind keine Zugangsdaten gespeichert.'
      : credentialsKept || !addressEntered
        ? 'Leer lassen, um die gespeicherten Zugangsdaten beizubehalten.'
        : 'Die Adresse zeigt auf einen anderen Server - bitte die Zugangsdaten neu eingeben.'

  function connectionPayload() {
    return {
      sourceUrl: values.sourceUrl.trim(),
      sourceProxy: values.sourceProxy.trim() || undefined,
      sourceInsecureSsl: values.sourceInsecureSsl,
      sourceCredentials: nextcloudCredentialsOf(values),
      libraryId: mode === 'edit' ? libraryId : undefined,
    }
  }

  async function handleTest() {
    const token = testToken
    setTesting(true)
    try {
      const result = await testLibrarySource({
        sourceType: 'NEXTCLOUD',
        ...connectionPayload(),
        sourceSettings: { folders: nextcloudFoldersOf(values) },
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
      const result = await browseSource('NEXTCLOUD', connectionPayload())
      setListing({
        token,
        options: result.entries,
        message:
          result.entries.length === 0
            ? { severity: 'warning', text: 'Der technische Nutzer sieht keine Ordner.' }
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
    const current = nextcloudFoldersOf(values).filter((entry) => entry !== '/')
    if (!current.includes(folder)) {
      onChange({ folders: [...current, folder].join('\n') })
    }
  }

  return (
    <Box>
      {isCreate && (
        <Typography component="h3" sx={{ fontSize: 16, fontWeight: 600, mb: 1.75 }}>
          Verbindung zur Nextcloud
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
          <FieldLabel htmlFor={`${idPrefix}-url`}>Adresse der Nextcloud</FieldLabel>
          <TextField
            id={`${idPrefix}-url`}
            size="small"
            fullWidth
            value={values.sourceUrl}
            onChange={(e) => onChange({ sourceUrl: e.target.value })}
            placeholder="https://cloud.example.org"
            helperText="Auch die WebDAV-Adresse aus der Nextcloud wird angenommen."
            slotProps={{ htmlInput: { maxLength: 2000, sx: { fontFamily: 'monospace' } } }}
          />
        </Box>
        <Box>
          <FieldLabel htmlFor={`${idPrefix}-username`}>Technischer Nutzer</FieldLabel>
          <TextField
            id={`${idPrefix}-username`}
            size="small"
            fullWidth
            value={values.username}
            onChange={(e) => onChange({ username: e.target.value })}
            autoComplete="off"
            slotProps={{ htmlInput: { maxLength: 255 } }}
          />
        </Box>
        <Box>
          <FieldLabel htmlFor={`${idPrefix}-app-password`}>App-Passwort</FieldLabel>
          <TextField
            id={`${idPrefix}-app-password`}
            size="small"
            type="password"
            fullWidth
            value={values.appPassword}
            onChange={(e) => onChange({ appPassword: e.target.value })}
            helperText={credentialsHint}
            autoComplete="new-password"
            slotProps={{ htmlInput: { maxLength: 3800 } }}
          />
        </Box>
        <Box>
          <FieldLabel htmlFor={`${idPrefix}-proxy`}>Proxy (optional)</FieldLabel>
          <TextField
            id={`${idPrefix}-proxy`}
            size="small"
            fullWidth
            value={values.sourceProxy}
            onChange={(e) => onChange({ sourceProxy: e.target.value })}
            placeholder="proxy.example.com:8080"
            autoComplete="off"
            slotProps={{ htmlInput: { maxLength: 255 } }}
          />
        </Box>
        <FormControlLabel
          control={
            <Switch
              checked={values.sourceInsecureSsl}
              onChange={(e) => onChange({ sourceInsecureSsl: e.target.checked })}
            />
          }
          label="Zertifikatsprüfung aussetzen"
        />
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
            helperText="Ein Ordner je Zeile, wie der technische Nutzer ihn sieht, etwa /Projekte. / liest alles, auch Freigaben und Gruppenordner."
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
