import { useEffect, useRef, useState } from 'react'
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
import BusyButton from '../a11y/BusyButton'
import type { SourceBrowseResponse } from '../../types/api'
import { browseSource, testLibrarySource } from '../../services/libraryApi'
import {
  MAX_SHAREPOINT_FOLDERS,
  MAX_SHAREPOINT_LIBRARIES,
  SHAREPOINT_API,
  sharePointCoverageLabel,
  sharePointFolderLabel,
  sharePointFolderPath,
  sharePointKeyOf,
  sharePointLibraryChecksOf,
  sharePointLibraryLabel,
  sharePointLibraryName,
  sharePointSettingsOf,
  type SharePointFolder,
  type SharePointLibraryCheck,
  type SharePointLibraryChoice,
  type SharePointSourceValues,
} from '../../utils/sharePointSource'
import FieldLabel from '../wizard/FieldLabel'
import ScopeConsequence from './sources/ScopeConsequence'
import { connectionFields, type ConnectionFields } from './sources/sourceConnection'

interface Message {
  severity: 'success' | 'warning' | 'error' | 'info'
  text: string
}

interface Entry {
  id: string
  name: string | null
}

/** The level of a document library the folder picker shows: its root or a folder below it. */
interface FolderLevel {
  driveId: string
  trail: Entry[]
  entries: Entry[] | null
  message: Message | null
}

interface SharePointSourceFormProps {
  mode: 'create' | 'edit'
  values: SharePointSourceValues
  onChange: (patch: Partial<SharePointSourceValues>) => void
  /** Edit mode: lets the test and the listing go through the library's own profile. */
  libraryId?: string
  idPrefix: string
  /** What the connection profile decides; SharePoint signs in only through one. */
  connection?: ConnectionFields
}

/** The entries of a listing of one kind (`site`, `drive`, `folder`), in the source's order. */
function entriesOf(result: SourceBrowseResponse, kind: 'site' | 'drive' | 'folder'): Entry[] {
  return result.entries.flatMap((entry) => {
    const parsed = sharePointKeyOf(entry.key)
    return parsed?.kind === kind ? [{ id: parsed.id, name: entry.name ?? null }] : []
  })
}

function failure(err: unknown, fallback: string): Message {
  return { severity: 'error', text: err instanceof Error ? err.message : fallback }
}

/**
 * The SharePoint form (ADR-0040, Nachtrag „SharePoint“): find a site by its address or - with
 * Sites.Read.All - by a search term, choose document libraries of it, optionally narrow each to
 * folders, and test every chosen library. The profile signs in; the form asks for no secret and
 * shows no address. Every listing goes through the same browse endpoint, one stage per request.
 */
export default function SharePointSourceForm({
  mode,
  values,
  onChange,
  libraryId,
  idPrefix,
  connection = connectionFields({
    mode,
    sourceType: 'SHAREPOINT',
    idPrefix,
    libraryId,
    credentialsStored: false,
  }),
}: SharePointSourceFormProps) {
  const [siteUrl, setSiteUrl] = useState('')
  const [search, setSearch] = useState('')
  const [sites, setSites] = useState<Entry[] | null>(null)
  const [site, setSite] = useState<Entry | null>(null)
  const [drives, setDrives] = useState<Entry[] | null>(null)
  const [siteMessage, setSiteMessage] = useState<Message | null>(null)
  const [drivesMessage, setDrivesMessage] = useState<Message | null>(null)
  const [loadingSites, setLoadingSites] = useState<'address' | 'search' | null>(null)
  const [loadingDrives, setLoadingDrives] = useState(false)
  const [folders, setFolders] = useState<FolderLevel | null>(null)
  const [loadingFolders, setLoadingFolders] = useState(false)
  const [testing, setTesting] = useState(false)
  const [testMessage, setTestMessage] = useState<Message | null>(null)
  const [checks, setChecks] = useState<SharePointLibraryCheck[] | null>(null)
  // one counter per stage: a newer request of the same stage wins, the others stay
  const siteGeneration = useRef(0)
  const drivesGeneration = useRef(0)
  const foldersGeneration = useRef(0)
  const testGeneration = useRef(0)
  // the „Ordner wählen“ button of each chosen library, to return the focus once its picker closes
  const folderButtons = useRef(new Map<string, HTMLButtonElement>())

  const profile = connection.connection
  const usable = profile !== null || mode === 'edit'

  const changeLibraries = (libraries: SharePointLibraryChoice[]) => {
    testGeneration.current++
    setTesting(false)
    setTestMessage(null)
    setChecks(null)
    onChange({ libraries })
  }

  const browse = (query: Record<string, unknown>) =>
    browseSource(
      'SHAREPOINT',
      connection.probeRequest({ sourceUrl: SHAREPOINT_API, sourceInsecureSsl: false, query }),
    )

  const loadDrives = async (chosen: Entry) => {
    const mine = ++drivesGeneration.current
    foldersGeneration.current++
    setFolders(null)
    setSite(chosen)
    setDrives(null)
    setDrivesMessage(null)
    setLoadingDrives(true)
    try {
      const result = await browse({ site: chosen.id })
      if (drivesGeneration.current !== mine) return
      if (!result.complete) {
        setDrivesMessage({
          severity: 'warning',
          text: result.message ?? 'Die Dokumentbibliotheken sind nicht auflistbar.',
        })
        return
      }
      const found = entriesOf(result, 'drive')
      setDrives(found)
      if (found.length === 0) {
        setDrivesMessage({
          severity: 'info',
          text: `Die Site „${chosen.name ?? chosen.id}“ hat keine Dokumentbibliothek, die die Anwendung lesen kann. OneDrive und andere Laufwerke liest der Konnektor nicht.`,
        })
      }
    } catch (err) {
      if (drivesGeneration.current !== mine) return
      setDrivesMessage(failure(err, 'Die Dokumentbibliotheken konnten nicht geladen werden.'))
    } finally {
      if (drivesGeneration.current === mine) setLoadingDrives(false)
    }
  }

  const findSites = async (how: 'address' | 'search') => {
    const mine = ++siteGeneration.current
    drivesGeneration.current++
    foldersGeneration.current++
    setLoadingSites(how)
    setLoadingDrives(false)
    setSiteMessage(null)
    setSites(null)
    setSite(null)
    setDrives(null)
    setDrivesMessage(null)
    setFolders(null)
    try {
      const result = await browse(
        how === 'address' ? { siteUrl: siteUrl.trim() } : { search: search.trim() },
      )
      if (siteGeneration.current !== mine) return
      if (!result.complete) {
        setSiteMessage({ severity: 'warning', text: result.message ?? 'Keine Site gefunden.' })
        return
      }
      const found = entriesOf(result, 'site')
      if (found.length === 0) {
        setSiteMessage({
          severity: 'info',
          text: `Zu „${search.trim()}“ findet Microsoft Graph keine Site. Mit der Adresse der Site lässt sie sich direkt öffnen.`,
        })
        return
      }
      if (how === 'address' || found.length === 1) {
        setLoadingSites(null)
        await loadDrives(found[0])
        return
      }
      setSites(found)
    } catch (err) {
      if (siteGeneration.current !== mine) return
      setSiteMessage(failure(err, 'Die Site konnte nicht geladen werden.'))
    } finally {
      if (siteGeneration.current === mine) setLoadingSites(null)
    }
  }

  const chosen = new Map(values.libraries.map((library) => [library.driveId, library]))

  const toggleDrive = (drive: Entry) => {
    if (chosen.has(drive.id)) {
      if (folders?.driveId === drive.id) setFolders(null)
      changeLibraries(values.libraries.filter((library) => library.driveId !== drive.id))
      return
    }
    changeLibraries([
      ...values.libraries,
      {
        driveId: drive.id,
        name: sharePointLibraryName(drive.name, site?.name ?? null),
        folders: [],
      },
    ])
  }

  const removeLibrary = (driveId: string) => {
    if (folders?.driveId === driveId) {
      foldersGeneration.current++
      setFolders(null)
    }
    changeLibraries(values.libraries.filter((library) => library.driveId !== driveId))
  }

  const openLevel = async (driveId: string, trail: Entry[]) => {
    const mine = ++foldersGeneration.current
    setFolders({ driveId, trail, entries: null, message: null })
    setLoadingFolders(true)
    try {
      const below = trail.at(-1)
      const result = await browse(below ? { drive: driveId, folder: below.id } : { drive: driveId })
      if (foldersGeneration.current !== mine) return
      setFolders({
        driveId,
        trail,
        entries: result.complete ? entriesOf(result, 'folder') : null,
        message: result.complete
          ? null
          : { severity: 'warning', text: result.message ?? 'Die Ordner sind nicht auflistbar.' },
      })
    } catch (err) {
      if (foldersGeneration.current !== mine) return
      setFolders({
        driveId,
        trail,
        entries: null,
        message: failure(err, 'Die Ordner konnten nicht geladen werden.'),
      })
    } finally {
      if (foldersGeneration.current === mine) setLoadingFolders(false)
    }
  }

  const closeFolders = (returnFocus = false) => {
    const driveId = folders?.driveId
    foldersGeneration.current++
    setLoadingFolders(false)
    setFolders(null)
    if (returnFocus && driveId) folderButtons.current.get(driveId)?.focus()
  }

  const setFoldersOf = (driveId: string, next: SharePointFolder[]) => {
    changeLibraries(
      values.libraries.map((library) =>
        library.driveId === driveId ? { ...library, folders: next } : library,
      ),
    )
  }

  const toggleFolder = (library: SharePointLibraryChoice, folder: Entry, trail: Entry[]) => {
    const selected = library.folders.some((f) => f.id === folder.id)
    setFoldersOf(
      library.driveId,
      selected
        ? library.folders.filter((f) => f.id !== folder.id)
        : [
            ...library.folders,
            {
              id: folder.id,
              name: sharePointFolderPath(
                trail.map((step) => step.name ?? '…'),
                folder.name,
              ),
            },
          ],
    )
  }

  const runTest = async () => {
    const mine = ++testGeneration.current
    setTesting(true)
    setTestMessage(null)
    setChecks(null)
    try {
      const result = await testLibrarySource(
        connection.probeRequest({
          sourceType: 'SHAREPOINT',
          sourceUrl: SHAREPOINT_API,
          sourceInsecureSsl: false,
          sourceSettings: sharePointSettingsOf(values),
        }),
      )
      if (testGeneration.current !== mine) return
      setTestMessage({ severity: result.reachable ? 'success' : 'warning', text: result.message })
      setChecks(sharePointLibraryChecksOf(result.details))
    } catch (err) {
      if (testGeneration.current !== mine) return
      setTestMessage(failure(err, 'Der Verbindungstest ist fehlgeschlagen.'))
    } finally {
      if (testGeneration.current === mine) setTesting(false)
    }
  }

  const siteLabel = site ? (site.name ?? site.id) : ''
  const folderLibrary = folders ? chosen.get(folders.driveId) : undefined
  const failedChecks = checks?.filter((check) => !check.reachable) ?? []

  return (
    <Stack spacing={2.5}>
      {profile ? (
        <Typography variant="body2" data-testid="sharepoint-profile">
          Anmeldung über den Zugang „{profile.name}“ mit der App-Registrierung in Microsoft Entra.
          Die Bibliothek trägt keine eigenen Zugangsdaten.
        </Typography>
      ) : (
        mode === 'create' && (
          <Alert severity="info" data-testid="sharepoint-needs-profile">
            SharePoint ist nur über einen Zugang nutzbar. Bitte zuerst einen Zugang wählen.
          </Alert>
        )
      )}

      <Box component="section" aria-labelledby={`${idPrefix}-site-heading`}>
        <Typography
          id={`${idPrefix}-site-heading`}
          variant="subtitle2"
          component="h3"
          sx={{ mb: 1 }}
        >
          Site finden
        </Typography>
        <FieldLabel htmlFor={`${idPrefix}-site-url`}>Adresse der Site</FieldLabel>
        <Stack direction="row" spacing={1} sx={{ alignItems: 'flex-start' }}>
          <TextField
            id={`${idPrefix}-site-url`}
            size="small"
            fullWidth
            value={siteUrl}
            onChange={(e) => setSiteUrl(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && siteUrl.trim() !== '' && usable) {
                e.preventDefault()
                void findSites('address')
              }
            }}
            placeholder="https://contoso.sharepoint.com/sites/team"
            helperText="Die Adresse aus dem Browser. OPAA ruft sie nicht selbst auf, sondern fragt Microsoft Graph nach dieser Site."
            autoComplete="off"
            slotProps={{ htmlInput: { maxLength: 2000 } }}
          />
          <BusyButton
            variant="outlined"
            onClick={() => void findSites('address')}
            disabled={!usable || siteUrl.trim() === ''}
            busy={loadingSites === 'address'}
            busyAnnouncement="Site wird geladen"
            sx={{ flexShrink: 0 }}
          >
            Site öffnen
          </BusyButton>
        </Stack>
        <Box sx={{ mt: 1.5 }}>
          <FieldLabel htmlFor={`${idPrefix}-site-search`}>Oder nach Sites suchen</FieldLabel>
        </Box>
        <Stack direction="row" spacing={1} sx={{ alignItems: 'flex-start' }}>
          <TextField
            id={`${idPrefix}-site-search`}
            size="small"
            fullWidth
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && search.trim() !== '' && usable) {
                e.preventDefault()
                void findSites('search')
              }
            }}
            helperText="Die Suche setzt die Berechtigung Sites.Read.All voraus. Mit Sites.Selected die Adresse der Site eingeben."
            autoComplete="off"
            slotProps={{ htmlInput: { maxLength: 200 } }}
          />
          <BusyButton
            variant="outlined"
            onClick={() => void findSites('search')}
            disabled={!usable || search.trim() === ''}
            busy={loadingSites === 'search'}
            busyAnnouncement="Sites werden gesucht"
            sx={{ flexShrink: 0 }}
          >
            Sites suchen
          </BusyButton>
        </Stack>
        <Box role="status" aria-live="polite" data-testid="sharepoint-site-status">
          {siteMessage && (
            <Alert severity={siteMessage.severity} sx={{ mt: 1 }} role="none">
              {siteMessage.text}
            </Alert>
          )}
        </Box>
        {sites && sites.length > 0 && (
          <List dense aria-label="Gefundene Sites">
            {sites.map((entry) => (
              <ListItem key={entry.id} disableGutters>
                <Button
                  variant={site?.id === entry.id ? 'contained' : 'text'}
                  size="small"
                  onClick={() => void loadDrives(entry)}
                  aria-pressed={site?.id === entry.id}
                >
                  {entry.name ?? entry.id}
                </Button>
              </ListItem>
            ))}
          </List>
        )}
        {site && (
          <Box sx={{ mt: 1.5 }} data-testid="sharepoint-site-drives">
            <Typography variant="body2" sx={{ fontWeight: 600 }}>
              Dokumentbibliotheken der Site „{siteLabel}“
            </Typography>
            {loadingDrives && (
              <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                Dokumentbibliotheken werden geladen …
              </Typography>
            )}
            <Box role="status" aria-live="polite">
              {drivesMessage && (
                <Alert severity={drivesMessage.severity} sx={{ mt: 1 }} role="none">
                  {drivesMessage.text}
                </Alert>
              )}
            </Box>
            {drives && drives.length > 0 && (
              <List dense aria-label={`Dokumentbibliotheken der Site ${siteLabel}`}>
                {drives.map((drive) => (
                  <ListItem key={drive.id} disableGutters>
                    <FormControlLabel
                      control={
                        <Checkbox
                          checked={chosen.has(drive.id)}
                          disabled={
                            !chosen.has(drive.id) &&
                            values.libraries.length >= MAX_SHAREPOINT_LIBRARIES
                          }
                          onChange={() => toggleDrive(drive)}
                        />
                      }
                      label={drive.name ?? drive.id}
                    />
                  </ListItem>
                ))}
              </List>
            )}
          </Box>
        )}
      </Box>

      <Box component="section" aria-labelledby={`${idPrefix}-libraries-heading`}>
        <Typography
          id={`${idPrefix}-libraries-heading`}
          variant="subtitle2"
          component="h3"
          sx={{ mb: 1 }}
        >
          Gewählte Dokumentbibliotheken ({values.libraries.length} von höchstens{' '}
          {MAX_SHAREPOINT_LIBRARIES})
        </Typography>
        <ScopeConsequence testId="sharepoint-sharing-consequence">
          Alles, was aus diesen Dokumentbibliotheken indiziert wird, ist für alle Leseberechtigten
          dieser Bibliothek sichtbar, unabhängig von den Berechtigungen in SharePoint.
        </ScopeConsequence>
        {values.libraries.length === 0 ? (
          <Typography variant="body2" sx={{ color: 'text.secondary', mt: 1 }}>
            Noch keine Dokumentbibliothek gewählt.
          </Typography>
        ) : (
          <List dense aria-label="Gewählte Dokumentbibliotheken">
            {values.libraries.map((library) => {
              const label = sharePointLibraryLabel(library)
              const check = checks?.find((c) => c.driveId === library.driveId)
              return (
                <ListItem
                  key={library.driveId}
                  disableGutters
                  sx={{ display: 'block' }}
                  data-testid={`sharepoint-library-${library.driveId}`}
                >
                  <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                    <Typography variant="body2" sx={{ flex: 1, minWidth: 0 }}>
                      <strong>{label}</strong> · {sharePointCoverageLabel(library)}
                      {check &&
                        (check.reachable
                          ? ' · erreichbar'
                          : ` · ${check.message ?? 'nicht erreichbar'}`)}
                    </Typography>
                    <Button
                      ref={(button: HTMLButtonElement | null) => {
                        if (button) folderButtons.current.set(library.driveId, button)
                        else folderButtons.current.delete(library.driveId)
                      }}
                      size="small"
                      variant="text"
                      onClick={() =>
                        folders?.driveId === library.driveId
                          ? closeFolders()
                          : void openLevel(library.driveId, [])
                      }
                      aria-expanded={folders?.driveId === library.driveId}
                      aria-label={`Ordner von „${label}“ wählen`}
                      disabled={!usable}
                    >
                      Ordner wählen
                    </Button>
                    <IconButton
                      edge="end"
                      size="small"
                      aria-label={`Dokumentbibliothek „${label}“ entfernen`}
                      onClick={() => removeLibrary(library.driveId)}
                    >
                      <DeleteIcon fontSize="small" />
                    </IconButton>
                  </Stack>
                  {folders && folderLibrary && folders.driveId === library.driveId && (
                    <FolderPicker
                      idPrefix={idPrefix}
                      library={folderLibrary}
                      level={folders}
                      loading={loadingFolders}
                      onOpen={(trail) => void openLevel(library.driveId, trail)}
                      onToggle={(folder) => toggleFolder(folderLibrary, folder, folders.trail)}
                      onRemove={(id) =>
                        setFoldersOf(
                          library.driveId,
                          folderLibrary.folders.filter((f) => f.id !== id),
                        )
                      }
                      onClose={() => closeFolders(true)}
                    />
                  )}
                </ListItem>
              )
            })}
          </List>
        )}
      </Box>

      <Box>
        <BusyButton
          variant="contained"
          onClick={() => void runTest()}
          disabled={!usable || values.libraries.length === 0}
          busy={testing}
          busyAnnouncement="Verbindung wird geprüft"
        >
          {testing ? 'Prüft …' : 'Verbindung testen'}
        </BusyButton>
        {/* always in the DOM, so a screen reader announces the text that appears in it */}
        <Box role="status" aria-live="polite" data-testid="sharepoint-test-status">
          {testMessage && (
            <Alert severity={testMessage.severity} sx={{ mt: 1 }} role="none">
              {testMessage.text}
              {failedChecks.length > 0 && (
                <>
                  {' '}
                  {failedChecks
                    .map((check) => {
                      const library = chosen.get(check.driveId)
                      const label = library ? sharePointLibraryLabel(library) : check.driveId
                      return `${label}: ${check.message ?? 'nicht erreichbar'}`
                    })
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

interface FolderPickerProps {
  idPrefix: string
  library: SharePointLibraryChoice
  level: FolderLevel
  loading: boolean
  onOpen: (trail: Entry[]) => void
  onToggle: (folder: Entry) => void
  onRemove: (id: string) => void
  onClose: () => void
}

/** The folders of one level of a chosen document library, to narrow the library to some of them. */
function FolderPicker({
  idPrefix,
  library,
  level,
  loading,
  onOpen,
  onToggle,
  onRemove,
  onClose,
}: FolderPickerProps) {
  const label = sharePointLibraryLabel(library)
  const headingId = `${idPrefix}-folders-${library.driveId}`
  const heading = useRef<HTMLHeadingElement | null>(null)
  const where = ['Stamm', ...level.trail.map((step) => step.name ?? '…')].join(' / ')
  const selected = new Set(library.folders.map((folder) => folder.id))
  const full = library.folders.length >= MAX_SHAREPOINT_FOLDERS

  // the picker takes the focus when it opens; a change of level keeps it on the heading, which
  // stays while the list it replaces is gone - the status region then names the new level
  useEffect(() => {
    heading.current?.focus()
  }, [])
  const goTo = (trail: Entry[]) => {
    heading.current?.focus()
    onOpen(trail)
  }

  return (
    <Box
      component="section"
      aria-labelledby={headingId}
      sx={{ mt: 1, ml: 2, pl: 1.5, borderLeft: 2, borderColor: 'divider' }}
    >
      <Typography
        id={headingId}
        ref={heading}
        tabIndex={-1}
        variant="body2"
        component="h4"
        sx={{ fontWeight: 600 }}
      >
        Ordner in „{label}“
      </Typography>
      <Typography variant="caption" sx={{ color: 'text.secondary', display: 'block' }}>
        Ohne Ordner liest OPAA die ganze Dokumentbibliothek, mit Ordnern nur Dateien in oder unter
        ihnen.
      </Typography>
      {library.folders.length > 0 && (
        <List dense aria-label={`Gewählte Ordner in „${label}“`}>
          {library.folders.map((folder) => (
            <ListItem
              key={folder.id}
              disableGutters
              secondaryAction={
                <IconButton
                  edge="end"
                  size="small"
                  aria-label={`Ordner „${sharePointFolderLabel(folder)}“ abwählen`}
                  onClick={() => onRemove(folder.id)}
                >
                  <DeleteIcon fontSize="small" />
                </IconButton>
              }
            >
              <Typography variant="body2">{sharePointFolderLabel(folder)}</Typography>
            </ListItem>
          ))}
        </List>
      )}
      <Box role="status" aria-live="polite" data-testid={`${idPrefix}-folder-level`}>
        <Typography variant="body2" sx={{ color: 'text.secondary', mt: 0.5 }}>
          {loading ? `Ordner werden geladen … (Ebene: ${where})` : `Ebene: ${where}`}
        </Typography>
        {level.message && (
          <Alert severity={level.message.severity} sx={{ mt: 1 }} role="none">
            {level.message.text}
          </Alert>
        )}
        {level.entries && level.entries.length === 0 && (
          <Typography variant="body2" sx={{ color: 'text.secondary', mt: 0.5 }}>
            Auf dieser Ebene liegen keine Ordner.
          </Typography>
        )}
      </Box>
      {level.entries && level.entries.length > 0 && (
        <List dense aria-label={`Ordner auf der Ebene ${where}`}>
          {level.entries.map((folder) => (
            <ListItem
              key={folder.id}
              disableGutters
              secondaryAction={
                <Button
                  size="small"
                  variant="text"
                  onClick={() => goTo([...level.trail, folder])}
                  aria-label={`Unterordner von „${folder.name ?? folder.id}“ anzeigen`}
                >
                  Öffnen
                </Button>
              }
            >
              <FormControlLabel
                control={
                  <Checkbox
                    checked={selected.has(folder.id)}
                    disabled={!selected.has(folder.id) && full}
                    onChange={() => onToggle(folder)}
                  />
                }
                label={folder.name ?? folder.id}
              />
            </ListItem>
          ))}
        </List>
      )}
      <Stack direction="row" spacing={1} sx={{ mt: 0.5 }}>
        {level.trail.length > 0 && (
          <Button size="small" variant="text" onClick={() => goTo(level.trail.slice(0, -1))}>
            Eine Ebene höher
          </Button>
        )}
        <Button size="small" variant="outlined" onClick={onClose}>
          Fertig
        </Button>
      </Stack>
    </Box>
  )
}
