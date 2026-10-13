import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Chip from '@mui/material/Chip'
import FormControlLabel from '@mui/material/FormControlLabel'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type { SourceBrowseEntry } from '../../types/api'
import { browseSource, testLibrarySource } from '../../services/libraryApi'
import {
  DROPBOX_API,
  dropboxSettingsFrom,
  type DropboxSourceValues,
} from '../../utils/dropboxSource'
import BusyButton from '../a11y/BusyButton'
import FieldLabel from '../wizard/FieldLabel'
import type { ConnectionFields } from './sources/sourceConnection'
import { useSourceIssue, withIssue } from './sources/sourceIssueContext'

interface DropboxSourceFormProps {
  mode: 'create' | 'edit'
  idPrefix: string
  values: DropboxSourceValues
  onChange: (patch: Partial<DropboxSourceValues>) => void
  /** The profile and the consent the form probes with; a Dropbox library always has a profile. */
  connection: ConnectionFields
}

interface Message {
  severity: 'success' | 'warning' | 'error'
  text: string
}

/**
 * The source form of a Dropbox library: the folders, one per line, and whether they lie in the
 * team space. The account is the one the library's consent was given as ("Quelle verbinden"), so
 * the form asks for no secret. „Ordner laden" offers the folders below a path as chips that add a
 * line; „Verbindung testen" checks every folder. Each result is shown only while the values it was
 * measured with are unchanged.
 */
export default function DropboxSourceForm({
  mode,
  idPrefix,
  values,
  onChange,
  connection,
}: DropboxSourceFormProps) {
  const foldersIssue = useSourceIssue()('folders')
  const isCreate = mode === 'create'
  const [browsePath, setBrowsePath] = useState('/')
  const [test, setTest] = useState<{ token: string; message: Message } | null>(null)
  const [listing, setListing] = useState<{
    token: string
    options: SourceBrowseEntry[] | null
    message: Message | null
  } | null>(null)
  const [testing, setTesting] = useState(false)
  const [loading, setLoading] = useState(false)

  // the fixed address of the connector, which the profile names as its server address
  const serverUrl = connection.connection?.serverUrl ?? DROPBOX_API
  const listingToken = JSON.stringify({
    probe: connection.probe,
    teamSpace: values.teamSpace,
    browsePath,
  })
  const testToken = JSON.stringify({
    probe: connection.probe,
    folders: values.folders,
    teamSpace: values.teamSpace,
  })
  const visibleTest = test?.token === testToken ? test.message : null
  const visibleListing = listing?.token === listingToken ? listing : null
  const folderOptions = visibleListing?.options ?? null
  const folderMessage = visibleListing?.message ?? null

  async function handleTest() {
    const token = testToken
    setTesting(true)
    try {
      const result = await testLibrarySource(
        connection.probeRequest({
          sourceType: 'DROPBOX',
          sourceUrl: serverUrl,
          sourceSettings: dropboxSettingsFrom(values),
        }),
      )
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
    const token = listingToken
    setLoading(true)
    try {
      const result = await browseSource(
        'DROPBOX',
        connection.probeRequest({
          sourceUrl: serverUrl,
          query: { path: browsePath.trim() || '/', teamSpace: values.teamSpace },
        }),
      )
      setListing({
        token,
        options: result.entries,
        message: !result.complete
          ? { severity: 'warning', text: result.message ?? 'Ordner konnten nicht geladen werden' }
          : result.entries.length === 0
            ? { severity: 'warning', text: 'Unter diesem Pfad liegen keine Ordner.' }
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
    const current = values.folders
      .split('\n')
      .map((line) => line.trim())
      .filter((line) => line !== '' && line !== '/')
    if (!current.includes(folder)) {
      onChange({ folders: [...current, folder].join('\n') })
    }
  }

  return (
    <Box>
      {isCreate && (
        <Typography component="h3" sx={{ fontSize: 16, fontWeight: 600, mb: 1.75 }}>
          Ordner in Dropbox
        </Typography>
      )}
      <Box sx={{ display: 'grid', gridTemplateColumns: '1fr', gap: '14px' }}>
        <FormControlLabel
          control={
            <Switch
              id={`${idPrefix}-team-space`}
              checked={values.teamSpace}
              onChange={(e) => onChange({ teamSpace: e.target.checked })}
              slotProps={{ input: { 'aria-describedby': `${idPrefix}-team-space-help` } }}
            />
          }
          label="Team-Bereich lesen"
        />
        <Typography
          id={`${idPrefix}-team-space-help`}
          variant="body2"
          sx={{ mt: -1, color: 'text.secondary' }}
        >
          Aus: die Ordner des verbundenen Kontos. An: die Ordner des Team-Bereichs, wenn das Konto
          zu einem Dropbox-Team gehört.
        </Typography>
        <Box>
          <FieldLabel htmlFor={`${idPrefix}-folders`}>Ordner</FieldLabel>
          <TextField
            id={`${idPrefix}-folders`}
            size="small"
            fullWidth
            multiline
            minRows={2}
            value={values.folders}
            onChange={(e) => onChange({ folders: e.target.value })}
            error={foldersIssue !== null}
            helperText={withIssue(
              foldersIssue,
              'Ein Ordner je Zeile, wie das verbundene Konto ihn sieht, etwa /Projekte. / liest alles.',
            )}
            slotProps={{ htmlInput: { sx: { fontFamily: 'monospace' } } }}
          />
        </Box>
        <Box>
          <FieldLabel htmlFor={`${idPrefix}-browse-path`}>Ordner anzeigen unter</FieldLabel>
          <TextField
            id={`${idPrefix}-browse-path`}
            size="small"
            fullWidth
            value={browsePath}
            onChange={(e) => setBrowsePath(e.target.value)}
            helperText="„Ordner laden“ zeigt die Ordner direkt unter diesem Pfad."
            slotProps={{ htmlInput: { maxLength: 1000, sx: { fontFamily: 'monospace' } } }}
          />
          {folderOptions && folderOptions.length > 0 && (
            <Stack
              direction="row"
              spacing={0.5}
              useFlexGap
              sx={{ flexWrap: 'wrap', mt: 1 }}
              role="group"
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
        <Stack direction="row" spacing={1}>
          <BusyButton
            variant="outlined"
            onClick={() => void handleLoadFolders()}
            busy={loading}
            busyAnnouncement="Ordner werden geladen"
          >
            {loading ? 'Ordner werden geladen …' : 'Ordner laden'}
          </BusyButton>
          <BusyButton
            variant="outlined"
            onClick={() => void handleTest()}
            busy={testing}
            busyAnnouncement="Verbindung wird geprüft"
          >
            {testing ? 'Wird geprüft …' : 'Verbindung testen'}
          </BusyButton>
        </Stack>
        {visibleTest && <Alert severity={visibleTest.severity}>{visibleTest.text}</Alert>}
      </Box>
    </Box>
  )
}
