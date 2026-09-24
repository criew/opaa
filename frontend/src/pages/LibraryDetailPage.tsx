import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { Link as RouterLink, useNavigate, useParams, useSearchParams } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import Chip from '@mui/material/Chip'
import FormControlLabel from '@mui/material/FormControlLabel'
import IconButton from '@mui/material/IconButton'
import LinearProgress from '@mui/material/LinearProgress'
import Link from '@mui/material/Link'
import Menu from '@mui/material/Menu'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import Tab from '@mui/material/Tab'
import Tabs from '@mui/material/Tabs'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import AccountTreeIcon from '@mui/icons-material/AccountTree'
import FolderIcon from '@mui/icons-material/Folder'
import LanguageIcon from '@mui/icons-material/Language'
import MoreVertIcon from '@mui/icons-material/MoreVert'
import RssFeedIcon from '@mui/icons-material/RssFeed'
import StorageIcon from '@mui/icons-material/Storage'
import DataUsageIcon from '@mui/icons-material/DataUsage'
import DescriptionOutlinedIcon from '@mui/icons-material/DescriptionOutlined'
import HistoryIcon from '@mui/icons-material/History'
import PlayArrowIcon from '@mui/icons-material/PlayArrow'
import UploadFileIcon from '@mui/icons-material/UploadFile'
import WarningAmberIcon from '@mui/icons-material/WarningAmber'
import { alpha } from '@mui/material/styles'
import { fontFamily } from '../theme/tokens'
import type { AssetRole, DocumentSourceType, S3Settings, ConfluenceSpaceRef } from '../types/api'
import { confluenceEditionLabel } from '../utils/labels'
import { useAuthStore } from '../stores/authStore'
import { confirmAction } from '../stores/confirmStore'
import { useLibraryStore } from '../stores/libraryStore'
import { IDLE_RUN_STATE, useIndexingStore } from '../stores/indexingStore'
import {
  assetRoleLabel,
  documentSourceTypeConfigKind,
  documentSourceTypeLabel,
  formatFileSize,
} from '../utils/labels'
import AssetAccessDerivationSection from '../components/assets/AssetAccessDerivationSection'
import AssetDistributionSection from '../components/assets/AssetDistributionSection'
import AssetHeadlineEditor from '../components/assets/AssetHeadlineEditor'
import AssetSpacesList from '../components/assets/AssetSpacesList'
import LibraryExternalAccessSection from '../components/library/LibraryExternalAccessSection'
import LibraryDocumentsSection from '../components/library/LibraryDocumentsSection'
import LibrarySourceSection from '../components/library/LibrarySourceSection'
import LibraryMetadataFieldsSection from '../components/metadata/LibraryMetadataFieldsSection'
import MetadataExtractionSettingsSection from '../components/metadata/MetadataExtractionSettingsSection'
import PageSection from '../components/PageSection'
import MetaBadge from '../components/MetaBadge'
import SuccessionStateNote from '../components/succession/SuccessionStateNote'
import { successionAwareMessage } from '../components/succession/successionConflict'

function canEditLibrary(role: AssetRole | undefined): boolean {
  return role === 'MANAGER' || role === 'OWNER'
}

function canDeleteLibrary(role: AssetRole | undefined): boolean {
  return role === 'OWNER'
}

// ADR-0018, Entscheidung 2: auslösen darf, wer an der Bibliothek mindestens EDITOR ist - dieselbe
// Schwelle wie beim Hoch- und Löschen von Dokumenten.
function canManageDocuments(role: AssetRole | undefined): boolean {
  return role === 'EDITOR' || role === 'MANAGER' || role === 'OWNER'
}

/**
 * #1191: names an unreadable space the way the documents area does ("Name (KEY)"), from the
 * library's own space selection - the key alone when the selection no longer carries the space.
 */
function confluenceSpaceHeroLabel(
  key: string,
  spaces: ConfluenceSpaceRef[] | null | undefined,
): string {
  const name = spaces?.find((space) => space.key === key)?.name
  return name ? `${name} (${key})` : key
}

/** The head's one-line Umfang of a Confluence library - the detail lives in the "Quelle" area. */
function confluenceScopeSummaryLabel(spaces: ConfluenceSpaceRef[] | null | undefined): string {
  const count = spaces?.length ?? 0
  return count === 1 ? '1 Space' : `${count} Spaces`
}

/** The same one-liner for an S3 library's scopes (ADR-0027). */
function s3ScopeSummaryLabel(settings: S3Settings | null | undefined): string {
  const count = settings?.scopes?.length ?? 0
  return count === 1 ? '1 Geltungsbereich' : `${count} Geltungsbereiche`
}

function formatIndexedAt(indexedAt: string | null | undefined): string {
  if (!indexedAt) return '—'
  return new Date(indexedAt).toLocaleString('de-DE', { dateStyle: 'medium', timeStyle: 'short' })
}

/** The page's areas; the active one is shareable via the "tab" search param (URL as state). */
type LibraryDetailTab = 'dokumente' | 'quelle' | 'metadaten' | 'freigaben'

/**
 * The area named by `?tab=`. Every role sees every area, so only the source area can be missing -
 * an UPLOAD library has no source. An unknown or no-longer-existing value (the pre-#1939 names
 * „indizierung" and „verwaltung" among them) falls back to the documents.
 */
function resolveTab(requested: string | null, hasSourceTab: boolean): LibraryDetailTab {
  switch (requested) {
    case 'quelle':
      return hasSourceTab ? 'quelle' : 'dokumente'
    case 'metadaten':
      return 'metadaten'
    case 'freigaben':
      return 'freigaben'
    default:
      return 'dokumente'
  }
}

/** The hero's per-source glyph - the one pictorial anchor of the page. */
function sourceGlyphIcon(sourceType: DocumentSourceType | undefined) {
  switch (sourceType) {
    case 'CONFLUENCE':
      return <AccountTreeIcon sx={{ fontSize: 24 }} />
    case 'FILESYSTEM':
      return <FolderIcon sx={{ fontSize: 24 }} />
    case 'HTTP_DIRECTORY':
      return <LanguageIcon sx={{ fontSize: 24 }} />
    case 'RSS_FEED':
      return <RssFeedIcon sx={{ fontSize: 24 }} />
    case 'S3':
      return <StorageIcon sx={{ fontSize: 24 }} />
    default:
      return <UploadFileIcon sx={{ fontSize: 24 }} />
  }
}

interface HeroStatTileProps {
  /** Small leading pictogram (aria-hidden) - or a status dot for the last-run tile. */
  icon: ReactNode
  children: ReactNode
}

/**
 * Eine Kennzahl der Bühne als Glied eines Bandes (#1609): keine Kachel, sondern ein Eintrag, den
 * eine senkrechte Haarlinie vom nächsten trennt. Der Text bleibt eine flache Aussage
 * („87 Dokumente") — ein Element für Screenreader und Textsuche gleichermaßen; das Symbol trägt
 * keinen eigenen Text.
 */
function HeroStatTile({ icon, children }: HeroStatTileProps) {
  return (
    <Box
      sx={{
        display: 'flex',
        alignItems: 'center',
        gap: 1,
        pr: 2.25,
        // Die Linie steht rechts statt links: Bricht das Band um, beginnt die erste Kennzahl jeder
        // Zeile bündig an der Seitenkante, ohne hängenden Strich davor.
        borderRight: 1,
        borderColor: 'divider',
        '&:last-of-type': { borderRight: 0, pr: 0 },
      }}
    >
      <Box aria-hidden sx={{ display: 'flex', color: 'text.disabled' }}>
        {icon}
      </Box>
      <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>{children}</Typography>
    </Box>
  )
}

interface DiagnosticsLockControlProps {
  locked: boolean
  canToggle: boolean
  saving: boolean
  error: string | null
  onToggle: () => void
  onDismissError: () => void
}

// #1257: renders the Diagnosesperre state (docs/features/hybrid-retrieval.md, Leitplanke (e)) -
// visible to everyone who may read the library (see LibraryResponse#diagnosticsLocked), but only
// togglable by the responsible body itself. The 403 a caller without that standing gets back from
// PUT .../diagnostics-lock is shown verbatim (see normalizeError) - it already names, in German,
// who may act instead.
function DiagnosticsLockControl({
  locked,
  canToggle,
  saving,
  error,
  onToggle,
  onDismissError,
}: DiagnosticsLockControlProps) {
  return (
    <Box>
      <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
        <Chip
          label={locked ? 'Diagnose gesperrt' : 'Diagnose freigegeben'}
          size="small"
          color={locked ? 'default' : 'warning'}
          variant="outlined"
        />
        {canToggle && (
          <Button
            size="small"
            variant="outlined"
            onClick={onToggle}
            disabled={saving}
            aria-label={locked ? 'Diagnosesperre lösen' : 'Diagnosesperre setzen'}
          >
            {saving ? 'Wird gespeichert …' : locked ? 'Sperre lösen' : 'Sperre setzen'}
          </Button>
        )}
      </Stack>
      <Typography variant="caption" component="p" sx={{ color: 'text.secondary', mt: 0.5 }}>
        Solange gesperrt, bleibt diese Bibliothek von einer Suchdiagnose im Rechtekontext einer
        anderen Person ausgeschlossen — dort ist dann weder ein Treffer noch ein Titel aus ihr zu
        sehen.
        {!canToggle &&
          ' Setzen und lösen kann die Sperre nur die für die Bibliothek zuständige Stelle (Eigentümer), nicht die Systemverwaltung als solche.'}
      </Typography>
      {error && (
        <Alert severity="error" sx={{ mt: 1 }} onClose={onDismissError}>
          {error}
        </Alert>
      )}
    </Box>
  )
}

interface ShareCapControlProps {
  allAccountsGrantAllowed: boolean
  listedCap: boolean
  saving: boolean
  error: string | null
  onSave: (allAccountsGrantAllowed: boolean, listedCap: boolean) => void
  onDismissError: () => void
}

/**
 * The system administration's own ceiling on a connector library (#797, in the shape #1931 gave
 * it) - visible and settable only here, never to the library's own owner: the owner already sees
 * the effect (a grant or a listing above the cap answers 409, shown verbatim by the surrounding
 * form) but not the control that sets it, mirroring how DiagnosticsLockControl above splits "who
 * sees the state" from "who may change it".
 */
function ShareCapControl({
  allAccountsGrantAllowed,
  listedCap,
  saving,
  error,
  onSave,
  onDismissError,
}: ShareCapControlProps) {
  const [draftAllAccounts, setDraftAllAccounts] = useState(allAccountsGrantAllowed)
  const [draftListedCap, setDraftListedCap] = useState(listedCap)
  const changed = draftAllAccounts !== allAccountsGrantAllowed || draftListedCap !== listedCap

  return (
    <Box sx={{ pt: 1, borderTop: '1px solid', borderColor: 'divider' }}>
      <Typography variant="subtitle2">Freigabe-Obergrenze (Systemverwaltung)</Typography>
      <Typography variant="caption" component="p" sx={{ color: 'text.secondary', mb: 1 }}>
        Legt fest, wie weit diese Konnektorbibliothek überhaupt freigegeben werden darf. Wird eine
        Erlaubnis entzogen, wird eine bereits bestehende Freigabe sofort zurückgenommen.
      </Typography>
      <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
        <FormControlLabel
          control={
            <Checkbox
              checked={draftAllAccounts}
              onChange={(e) => setDraftAllAccounts(e.target.checked)}
            />
          }
          label="Freigabe an alle Konten erlaubt"
        />
        <FormControlLabel
          control={
            <Checkbox
              checked={draftListedCap}
              onChange={(e) => setDraftListedCap(e.target.checked)}
            />
          }
          label="Auffindbarkeit im Katalog erlaubt"
        />
        <Button
          size="small"
          variant="outlined"
          disabled={saving || !changed}
          onClick={() => onSave(draftAllAccounts, draftListedCap)}
        >
          {saving ? 'Wird gespeichert …' : 'Obergrenze speichern'}
        </Button>
      </Stack>
      {error && (
        <Alert severity="error" sx={{ mt: 1 }} onClose={onDismissError}>
          {error}
        </Alert>
      )}
    </Box>
  )
}

export default function LibraryDetailPage() {
  const { libraryId } = useParams()
  const navigate = useNavigate()
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')

  const listEntry = useLibraryStore((s) => s.libraries.find((l) => l.id === libraryId))
  const details = useLibraryStore((s) => (libraryId ? s.libraryDetails[libraryId] : undefined))
  const loadLibraries = useLibraryStore((s) => s.loadLibraries)
  const loadLibraryDetails = useLibraryStore((s) => s.loadLibraryDetails)
  const updateExistingLibrary = useLibraryStore((s) => s.updateExistingLibrary)
  const deleteExistingLibrary = useLibraryStore((s) => s.deleteExistingLibrary)
  const setLibraryDiagnosticsLock = useLibraryStore((s) => s.setLibraryDiagnosticsLock)
  const setLibraryShareCap = useLibraryStore((s) => s.setLibraryShareCap)
  const storeError = useLibraryStore((s) => s.error)

  const [localError, setLocalError] = useState<string | null>(null)
  const [actionMenuAnchor, setActionMenuAnchor] = useState<HTMLElement | null>(null)
  const [diagnosticsLockError, setDiagnosticsLockError] = useState<string | null>(null)
  const [diagnosticsLockSaving, setDiagnosticsLockSaving] = useState(false)
  const [shareCapError, setShareCapError] = useState<string | null>(null)
  const [shareCapSaving, setShareCapSaving] = useState(false)
  const [searchParams] = useSearchParams()

  useEffect(() => {
    // The list entry (myRole, documentCount, sourceType) may not be loaded yet if this page was
    // opened directly (e.g. a bookmark) rather than via a link from the overview.
    if (!listEntry) {
      void loadLibraries()
    }
  }, [listEntry, loadLibraries])

  useEffect(() => {
    if (!libraryId) return
    void loadLibraryDetails(libraryId)
  }, [libraryId, loadLibraryDetails])

  const library = details ?? listEntry
  const roleGrantsEdit = canEditLibrary(library?.myRole)
  const roleGrantsDelete = canDeleteLibrary(library?.myRole)
  const canEdit = roleGrantsEdit || isSystemAdmin
  const canDelete = roleGrantsDelete || isSystemAdmin
  const isAdministrativeOverride = isSystemAdmin && !roleGrantsEdit
  const canTrigger = canManageDocuments(library?.myRole) || isSystemAdmin

  // The connector-only concerns (status polling, trigger actions, the "Indizierung" area) hang
  // off the details' sourceType; a plain UPLOAD library has none of them.
  const connectorSourceType = details && details.sourceType !== 'UPLOAD' ? details.sourceType : null
  const connectorConfigKind = connectorSourceType
    ? documentSourceTypeConfigKind[connectorSourceType]
    : null

  // #1939: every role sees every area; a reader simply finds less inside it. Only an UPLOAD
  // library genuinely has no source area.
  const showSourceTab = connectorSourceType != null
  const activeTab: LibraryDetailTab = resolveTab(searchParams.get('tab'), showSourceTab)

  // Status polling lives at page level, not inside the (hideable) "Indizierung" area: a running
  // indexing stays visible in the page head no matter which area is active (guidelines 5.7).
  const run = useIndexingStore((s) =>
    libraryId ? (s.runsByLibrary[libraryId] ?? IDLE_RUN_STATE) : IDLE_RUN_STATE,
  )
  const triggerIndexing = useIndexingStore((s) => s.triggerIndexing)
  const loadStatus = useIndexingStore((s) => s.loadStatus)
  const stopPolling = useIndexingStore((s) => s.stopPolling)

  useEffect(() => {
    if (!libraryId || !connectorSourceType) return
    void loadStatus(libraryId, connectorSourceType)
    return () => stopPolling(libraryId)
  }, [libraryId, connectorSourceType, loadStatus, stopPolling])

  const isRunning = run.status === 'RUNNING'
  const runProgressPercent =
    run.totalDocuments > 0
      ? Math.round(((run.documentCount + run.documentsSkipped) / run.totalDocuments) * 100)
      : 0

  // A finished run changed the bestand: reload the library head (document count, storage) and
  // bump the token the documents area reloads its current view on - no manual page reload.
  const [documentsRefreshToken, setDocumentsRefreshToken] = useState(0)
  const wasRunningRef = useRef(false)
  useEffect(() => {
    if (wasRunningRef.current && !isRunning && libraryId) {
      void loadLibraryDetails(libraryId)
      setDocumentsRefreshToken((token) => token + 1)
    }
    wasRunningRef.current = isRunning
  }, [isRunning, libraryId, loadLibraryDetails])

  /**
   * The area's own address - a tab is a link, so middle-click and „open in new tab" work, and the
   * other URL state of the page (open folder, Pflege-Anker filter) survives the change.
   */
  function tabSearch(tab: LibraryDetailTab): string {
    const next = new URLSearchParams(searchParams)
    if (tab === 'dokumente') next.delete('tab')
    else next.set('tab', tab)
    const query = next.toString()
    return query ? `?${query}` : ''
  }

  /**
   * The PUT replaces name, description and reach as a whole; each form sends its own fields and
   * the saved values of the other, so saving the master data never changes the reach.
   */
  async function saveLibrary(fields: {
    name: string
    description: string | null | undefined
    listed: boolean
  }) {
    if (!libraryId) return
    await updateExistingLibrary(libraryId, {
      name: fields.name.trim(),
      description: fields.description?.trim() || undefined,
      listed: fields.listed,
      // Bewusst kein Quellkonfigurationsfeld gesetzt: das Backend lässt die gespeicherte
      // Konfiguration unverändert, solange keines der sourcePath/sourceUrl/sourceProxy/
      // sourceCredentials/sourceInsecureSsl-Felder in der Anfrage vorhanden ist (ADR-0018). Das
      // Bearbeiten der Quellkonfiguration selbst laeuft ueber EditLibrarySourceDialog weiter
      // unten in dieser Datei (#516) - dieses Stammdaten-Formular hier ruehrt sie nicht an.
      sourceInsecureSsl: null,
    })
  }

  /**
   * Name and description from the head. The rejection is thrown on, not swallowed: the editor in
   * the head shows it next to the fields the draft still holds.
   */
  async function saveHeadline(nextName: string, nextDescription: string) {
    if (!library) return
    try {
      await saveLibrary({
        name: nextName,
        description: nextDescription,
        listed: library.listed,
      })
    } catch (err) {
      // Die Reichweite ist der eine Weg, den eine offene Nachfolge sperrt (ADR-0036/6): Die
      // Ablehnung nennt deshalb auch den Ausgang.
      throw new Error(successionAwareMessage(err, 'Aktualisierung fehlgeschlagen'), { cause: err })
    }
  }

  async function handleDelete() {
    if (!libraryId || !library) return
    // ADR-0018, Entscheidung 5: das Löschen einer Konnektorbibliothek entfernt auch ihren
    // gesamten indizierten Bestand - eine stärkere Wirkung als bei einer UPLOAD-Bibliothek, deren
    // Löschung blockiert bleibt, solange sie noch Dokumente enthält.
    const isConnectorLibrary = details != null && details.sourceType !== 'UPLOAD'
    const confirmed = await confirmAction({
      question: `Bibliothek "${library.name}" löschen?`,
      consequence: isConnectorLibrary
        ? 'Das entfernt auch alle indizierten Dokumente dieser Bibliothek. Diese Aktion kann nicht rückgängig gemacht werden.'
        : 'Diese Aktion kann nicht rückgängig gemacht werden.',
      confirmLabel: 'Löschen',
      tone: 'danger',
    })
    if (!confirmed) return
    setLocalError(null)
    try {
      await deleteExistingLibrary(libraryId)
      navigate('/libraries')
    } catch (err) {
      setLocalError(err instanceof Error ? err.message : 'Löschen fehlgeschlagen')
    }
  }

  // #1257: PUT .../diagnostics-lock takes the desired end state, not a toggle - this always
  // requests the opposite of the currently rendered state, so a stale double-click cannot request
  // the same state twice.
  async function handleToggleDiagnosticsLock() {
    if (!libraryId || !details) return
    setDiagnosticsLockError(null)
    setDiagnosticsLockSaving(true)
    try {
      await setLibraryDiagnosticsLock(libraryId, !details.diagnosticsLocked)
    } catch (err) {
      setDiagnosticsLockError(
        err instanceof Error ? err.message : 'Diagnosesperre konnte nicht geändert werden',
      )
    } finally {
      setDiagnosticsLockSaving(false)
    }
  }

  async function handleSaveShareCap(allAccountsGrantAllowed: boolean, listedCap: boolean) {
    if (!libraryId) return
    setShareCapError(null)
    setShareCapSaving(true)
    try {
      await setLibraryShareCap(libraryId, { allAccountsGrantAllowed, listedCap })
    } catch (err) {
      setShareCapError(
        err instanceof Error ? err.message : 'Freigabe-Obergrenze konnte nicht geändert werden',
      )
    } finally {
      setShareCapSaving(false)
    }
  }

  if (!libraryId) {
    return (
      <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 } }}>
        <Alert severity="error">Keine Bibliothek angegeben.</Alert>
      </Box>
    )
  }

  if (!library) {
    return (
      <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 } }}>
        {storeError ? (
          <Alert severity="error">{storeError}</Alert>
        ) : (
          <Typography sx={{ color: 'text.secondary' }}>Bibliothek wird geladen …</Typography>
        )}
      </Box>
    )
  }

  const isRssFeedRun = connectorSourceType === 'RSS_FEED'
  const runFailedSuffix =
    run.documentsFailed > 0 ? `, davon ${run.documentsFailed} fehlgeschlagen` : ''

  const tabs: Array<{ value: LibraryDetailTab; label: string }> = [
    { value: 'dokumente', label: 'Dokumente' },
    ...(showSourceTab ? ([{ value: 'quelle', label: 'Quelle' }] as const) : []),
    { value: 'metadaten', label: 'Metadaten' },
    { value: 'freigaben', label: 'Freigaben' },
  ]

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      {/* Quellen-Bühne (#1609): Identität, Kennzahlen, Umfang und Aktionen der Bibliothek — als
          Bühne mit einer Grundlinie, nicht als Kasten. Der zurückhaltende Akzentschein bleibt: Er
          gibt der Seite ihre Atmosphäre, ohne eine Fläche zu behaupten, und läuft nach unten in
          den Seitengrund aus. Der Auftritt ist ein gerichteter 200-ms-Einblendvorgang; reduzierte
          Bewegung schaltet ihn ab (guidelines 4.5). */}
      <Box
        component="header"
        sx={(theme) => ({
          position: 'relative',
          borderBottom: 1,
          borderColor: 'divider',
          pb: { xs: 2.5, md: 3 },
          mb: 3,
          '&::before': {
            content: '""',
            position: 'absolute',
            // Der Schein greift über die linke Seitenkante hinaus, damit er als Lichtstimmung
            // liest und nicht als Fläche mit einer eigenen Kante.
            top: -24,
            left: { xs: -20, md: -56 },
            right: 0,
            bottom: 0,
            pointerEvents: 'none',
            background: `radial-gradient(560px 240px at 0% 0%, ${alpha(theme.palette.primary.main, 0.1)}, transparent 72%)`,
          },
          '@keyframes opaaHeroIn': {
            from: { opacity: 0, transform: 'translateY(6px)' },
            to: { opacity: 1, transform: 'none' },
          },
          animation: 'opaaHeroIn 200ms cubic-bezier(0.22, 1, 0.36, 1) both',
          '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
        })}
      >
        <Stack
          direction={{ xs: 'column', md: 'row' }}
          sx={{
            justifyContent: 'space-between',
            alignItems: { md: 'flex-start' },
            gap: 2,
            position: 'relative',
          }}
        >
          <Stack direction="row" spacing={2} sx={{ minWidth: 0, alignItems: 'flex-start' }}>
            <Box
              aria-hidden
              sx={(theme) => ({
                width: 48,
                height: 48,
                flexShrink: 0,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                borderRadius: '10px',
                color: 'primary.main',
                bgcolor: alpha(theme.palette.primary.main, 0.1),
                border: `1px solid ${alpha(theme.palette.primary.main, 0.4)}`,
              })}
            >
              {sourceGlyphIcon(details?.sourceType)}
            </Box>
            <Box sx={{ minWidth: 0 }}>
              {/* Eyebrow (guidelines 3.3) - the source type is no longer repeated here: it is the
                  badge on the heading's baseline and the glyph beside it, and nowhere else. */}
              <Typography
                sx={{
                  fontFamily: fontFamily.mono,
                  fontSize: 10,
                  fontWeight: 500,
                  letterSpacing: '0.08em',
                  color: 'primary.main',
                  mb: 0.5,
                }}
              >
                WISSENSBIBLIOTHEK
              </Typography>
              <AssetHeadlineEditor
                // A fresh editor per library, so a draft never survives a change of library.
                key={`headline-${libraryId}`}
                name={library.name}
                description={library.description}
                idPrefix="library-detail"
                nameLabel="Name der Bibliothek"
                editLabel="Name und Beschreibung bearbeiten"
                canEdit={canEdit}
                onSave={saveHeadline}
                badges={
                  <>
                    {details && (
                      <MetaBadge>{documentSourceTypeLabel(details.sourceType)}</MetaBadge>
                    )}
                    <MetaBadge accent>{assetRoleLabel(library.myRole)}</MetaBadge>
                    {isAdministrativeOverride && <MetaBadge>administrativ</MetaBadge>}
                  </>
                }
              />
              {/* ADR-0036, Entscheidung 6: Zustand und Adressat für jeden Leseberechtigten -
                  ohne Datum, früheren Eigentümer oder Grund. */}
              <Box sx={{ maxWidth: 640 }}>
                <SuccessionStateNote succession={details?.succession} />
              </Box>
            </Box>
          </Stack>
          <Stack
            direction="row"
            spacing={1.5}
            useFlexGap
            sx={{ flexWrap: 'wrap', flexShrink: 0, pt: { md: 0.5 }, alignItems: 'flex-start' }}
          >
            {connectorSourceType && canTrigger && (
              <>
                <Button
                  variant="contained"
                  startIcon={<PlayArrowIcon />}
                  onClick={() => void triggerIndexing(libraryId, connectorSourceType)}
                  disabled={isRunning}
                >
                  {isRunning ? 'Indizierung läuft …' : 'Jetzt indizieren'}
                </Button>
                {/* ADR-0023, Entscheidung 4 (#1139): "Jetzt indizieren" follows the library's state
                    (incremental between two full runs); the full reconciliation can be forced. */}
                {connectorConfigKind === 'confluence' && (
                  <Button
                    variant="outlined"
                    onClick={() => void triggerIndexing(libraryId, connectorSourceType, 'FULL')}
                    disabled={isRunning}
                  >
                    Vollabgleich starten
                  </Button>
                )}
              </>
            )}
            {/* Das Löschen ist die eine folgenschwere Aktion der Seite - sie steht im „⋯"-Menü,
                nirgends sonst (#1939). Für eine Upload-Bibliothek steht das Menü allein. */}
            {canDelete && (
              <>
                <Tooltip title="Weitere Aktionen">
                  <IconButton
                    aria-label="Weitere Aktionen"
                    aria-haspopup="menu"
                    aria-expanded={actionMenuAnchor != null}
                    onClick={(event) => setActionMenuAnchor(event.currentTarget)}
                  >
                    <MoreVertIcon />
                  </IconButton>
                </Tooltip>
                <Menu
                  anchorEl={actionMenuAnchor}
                  open={actionMenuAnchor != null}
                  onClose={() => setActionMenuAnchor(null)}
                >
                  <MenuItem
                    onClick={() => {
                      setActionMenuAnchor(null)
                      void handleDelete()
                    }}
                    sx={{ color: 'error.main' }}
                  >
                    Bibliothek löschen
                  </MenuItem>
                </Menu>
              </>
            )}
          </Stack>
        </Stack>

        {/* Key figures - each tile one flat statement ("87 Dokumente"), one element for screen
            readers and text queries alike.
            #119: storageQuotaBytes/storageUsedBytes are only sent to a caller with at least
            MANAGER - a VIEWER's library object simply carries neither field.
            #518 review, finding 1: which wording the last-run tile uses (feed entries vs. plain
            document count) is decided by the library's own, unchanging sourceType. */}
        <Stack
          direction="row"
          spacing={2.25}
          useFlexGap
          sx={{ flexWrap: 'wrap', rowGap: 1, mt: 2.5, position: 'relative' }}
        >
          <HeroStatTile icon={<DescriptionOutlinedIcon sx={{ fontSize: 16 }} />}>
            {(library.documentCount ?? 0).toLocaleString('de-DE')}{' '}
            {(library.documentCount ?? 0) === 1 ? 'Dokument' : 'Dokumente'}
          </HeroStatTile>
          {details?.storageQuotaBytes != null && details.storageUsedBytes != null && (
            <HeroStatTile icon={<DataUsageIcon sx={{ fontSize: 16 }} />}>
              {formatFileSize(details.storageUsedBytes)} von{' '}
              {formatFileSize(details.storageQuotaBytes)} Speicherkontingent belegt
            </HeroStatTile>
          )}
          {connectorSourceType && canTrigger && !isRunning && run.status !== 'IDLE' && (
            <HeroStatTile
              icon={
                <Box
                  component="span"
                  sx={{
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: 0.75,
                    color: run.status === 'COMPLETED' ? 'success.main' : 'error.main',
                  }}
                >
                  <HistoryIcon sx={{ fontSize: 16 }} />
                </Box>
              }
            >
              Letzter Lauf: {run.status === 'COMPLETED' ? 'Abgeschlossen' : 'Fehlgeschlagen'}
              {' · '}
              {isRssFeedRun
                ? `${run.totalDocuments} Feed-Einträge, ${run.documentsSkipped} übersprungen, ${run.documentCount} indiziert (${run.documentsIndexedTotal} Dokumente insgesamt)${runFailedSuffix}`
                : `Dokumente: ${run.documentCount} verarbeitet${run.documentsSkipped > 0 ? ` (${run.documentsSkipped} übersprungen)` : ''}${runFailedSuffix}`}
              {run.timestamp ? ` · ${formatIndexedAt(run.timestamp)}` : ''}
            </HeroStatTile>
          )}
        </Stack>

        {/* #1939: Der Umfang steht im Kopf nur noch als Kurzzeile mit Sprung in den Reiter
            „Quelle"; dort steht er vollständig, für jede Rolle. Die Warnung über einen
            unvollständig gelesenen Umfang bleibt dagegen hier: Sie betrifft den Bestand, den
            jede Ansicht der Seite zeigt, nicht die Einstellung dahinter. */}
        {(details?.sourceType === 'CONFLUENCE' || details?.sourceType === 'S3') && (
          <Box
            sx={{
              mt: 2.5,
              pt: 2,
              borderTop: 1,
              borderTopColor: 'divider',
              position: 'relative',
              display: 'flex',
              flexDirection: 'column',
              gap: 1,
            }}
          >
            <Typography variant="body2" data-testid="library-scope-summary">
              {details.sourceType === 'CONFLUENCE'
                ? [
                    confluenceScopeSummaryLabel(details.confluenceSpaces),
                    details.confluenceEdition
                      ? confluenceEditionLabel(details.confluenceEdition)
                      : null,
                  ]
                    .filter(Boolean)
                    .join(' · ')
                : s3ScopeSummaryLabel(details.s3Settings)}
              {' · '}
              <Link
                component={RouterLink}
                to={{ search: tabSearch('quelle') }}
                replace
                underline="hover"
              >
                Details
              </Link>
            </Typography>
            {details.sourceType === 'CONFLUENCE' && run.unlistedScopeKeys.length > 0 && (
              <Stack
                direction="row"
                spacing={1}
                role="note"
                data-testid="confluence-incomplete-listing-warning"
                sx={{
                  alignItems: 'flex-start',
                  mt: 0.5,
                  pl: 1.5,
                  pr: 1.25,
                  py: 1,
                  // Hervorhebung als Signalkante links statt als Rahmen ringsum (#1608, Regel 4):
                  // dasselbe Mittel wie im Bestätigungs-Overlay, damit ein Hinweis überall gleich
                  // aussieht. Die Signalfarbe bleibt in Kante und Symbol — nie im Fließtext.
                  borderLeft: 3,
                  borderColor: 'warning.main',
                  bgcolor: (theme) => alpha(theme.palette.warning.main, 0.08),
                }}
              >
                <WarningAmberIcon
                  aria-hidden
                  sx={{ fontSize: 16, color: 'warning.main', mt: '2px', flexShrink: 0 }}
                />
                <Typography sx={{ fontSize: 12.5 }}>
                  {run.unlistedScopeKeys.length === 1
                    ? `Der letzte Vollabgleich konnte den Space ${confluenceSpaceHeroLabel(run.unlistedScopeKeys[0], details.confluenceSpaces)} nicht vollständig lesen; sein Bestand ist möglicherweise veraltet.`
                    : `Der letzte Vollabgleich konnte die Spaces ${run.unlistedScopeKeys.map((key) => confluenceSpaceHeroLabel(key, details.confluenceSpaces)).join(', ')} nicht vollständig lesen; ihr Bestand ist möglicherweise veraltet.`}{' '}
                  Der Hinweis bleibt, bis ein Vollabgleich wieder alle Spaces lesen kann.
                </Typography>
              </Stack>
            )}
            {details.sourceType === 'S3' && run.unlistedScopeKeys.length > 0 && (
              <Stack
                direction="row"
                spacing={1}
                role="note"
                data-testid="s3-incomplete-listing-warning"
                sx={{
                  alignItems: 'flex-start',
                  mt: 0.5,
                  pl: 1.5,
                  pr: 1.25,
                  py: 1,
                  borderLeft: 3,
                  borderColor: 'warning.main',
                  bgcolor: (theme) => alpha(theme.palette.warning.main, 0.08),
                }}
              >
                <WarningAmberIcon
                  aria-hidden
                  sx={{ fontSize: 16, color: 'warning.main', mt: '2px', flexShrink: 0 }}
                />
                <Typography sx={{ fontSize: 12.5 }}>
                  {run.unlistedScopeKeys.length === 1
                    ? `Der letzte Vollabgleich konnte den Geltungsbereich „${run.unlistedScopeKeys[0]}“ nicht auflisten; sein Bestand ist möglicherweise veraltet.`
                    : `Der letzte Vollabgleich konnte die Geltungsbereiche ${run.unlistedScopeKeys.map((key) => `„${key}“`).join(', ')} nicht auflisten; ihr Bestand ist möglicherweise veraltet.`}{' '}
                  Der Hinweis bleibt, bis ein Vollabgleich wieder alle Geltungsbereiche auflisten
                  kann.
                </Typography>
              </Stack>
            )}
          </Box>
        )}

        {/* A running indexing stays visible no matter which area below is active (guidelines
            5.7) - anchored in the hero with a pulsing dot as the one moving element. */}
        {connectorSourceType && isRunning && (
          <Box
            sx={{ mt: 2.5, pt: 2, borderTop: 1, borderTopColor: 'divider', position: 'relative' }}
          >
            <LinearProgress
              variant={run.totalDocuments > 0 ? 'determinate' : 'indeterminate'}
              value={runProgressPercent}
              sx={{ mb: 1, borderRadius: 999 }}
            />
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
              <Box
                aria-hidden
                sx={{
                  width: 8,
                  height: 8,
                  borderRadius: '999px',
                  bgcolor: 'primary.main',
                  '@keyframes opaaPulse': {
                    '0%': { opacity: 0.35 },
                    '50%': { opacity: 1 },
                    '100%': { opacity: 0.35 },
                  },
                  animation: 'opaaPulse 1.2s ease-in-out infinite',
                  '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
                }}
              />
              <Typography variant="body2" sx={{ color: 'text.secondary' }} role="status">
                {run.totalDocuments > 0
                  ? isRssFeedRun
                    ? `${run.documentCount + run.documentsSkipped} von ${run.totalDocuments} Feed-Einträgen verarbeitet (${run.documentsIndexedTotal} Dokumente indiziert)`
                    : `${run.documentCount + run.documentsSkipped} von ${run.totalDocuments} Dokumenten verarbeitet`
                  : isRssFeedRun
                    ? 'Feed-Einträge werden ermittelt …'
                    : 'Dokumente werden ermittelt …'}
              </Typography>
            </Stack>
          </Box>
        )}
      </Box>

      {localError && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setLocalError(null)}>
          {localError}
        </Alert>
      )}
      {/* #506 review, finding 3: without this, a failed GET /libraries/{id} while the list entry
          is already cached silently drops the typed areas below with no explanation - details
          stays undefined, so neither the UPLOAD nor the connector area's sourceType check
          matches. */}
      {!details && storeError && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {storeError}
        </Alert>
      )}
      {/* #1939: ein Hinweis auf die eigene Rolle, nicht zwei — der Dokumentenbereich trägt
          keinen zweiten mehr. Die beiden Stufen sagen Verschiedenes: Wer Dokumente pflegen darf,
          liest nicht bloß. */}
      {!canTrigger && (
        <Alert severity="info" sx={{ mb: 2 }}>
          Sie haben in dieser Bibliothek nur Leserechte.
        </Alert>
      )}
      {canTrigger && !canEdit && (
        <Alert severity="info" sx={{ mb: 2 }}>
          Sie können Dokumente dieser Bibliothek pflegen, ihre Einstellungen aber nicht ändern.
        </Alert>
      )}
      {isAdministrativeOverride && (
        <Alert severity="info" sx={{ mb: 2 }}>
          Sie bearbeiten diese Bibliothek als System-Administrator, nicht über eine eigene
          Berechtigung.
        </Alert>
      )}

      {/* Jeder Reiter ist ein Link auf dieselbe Seite mit anderem `?tab=` — Mittelklick und
          „in neuem Tab öffnen" funktionieren, und ein Lesezeichen landet im richtigen Bereich. */}
      <Tabs
        value={activeTab}
        aria-label="Bereiche dieser Bibliothek"
        sx={{
          borderBottom: 1,
          borderColor: 'divider',
          mb: 4,
          minHeight: 42,
          '& .MuiTab-root': {
            textTransform: 'none',
            fontSize: 13.5,
            fontWeight: 500,
            minHeight: 42,
            px: 2,
          },
        }}
      >
        {tabs.map((entry) => (
          <Tab
            key={entry.value}
            label={entry.label}
            value={entry.value}
            component={RouterLink}
            to={{ search: tabSearch(entry.value) }}
            // Der Bereich ist ein Zustand derselben Seite, kein eigener Halt: Wer vier Reiter
            // durchsieht, soll die Seite mit einem „Zurück" verlassen, nicht mit vieren.
            replace
            id={`library-tab-${entry.value}`}
            aria-controls={`library-tabpanel-${entry.value}`}
          />
        ))}
      </Tabs>

      {/* Every area stays mounted while another one is active: status polling, running uploads
          and loaded run protocols keep living, only their visibility toggles. */}
      <Box
        role="tabpanel"
        id="library-tabpanel-dokumente"
        aria-labelledby="library-tab-dokumente"
        hidden={activeTab !== 'dokumente'}
      >
        {details && (
          <LibraryDocumentsSection
            // Forces a remount on library change (rather than resetting local state like
            // searchInput from within an effect, which react-hooks/set-state-in-effect flags as a
            // cascading-render risk): a fresh component instance starts every piece of local
            // state at its initial value for free. The prefix keeps the key distinct from the
            // other keyed sections of this page - two siblings sharing the bare libraryId made
            // React duplicate the spaces section into stuck "Wird geladen …" clones.
            key={`documents-${libraryId}`}
            libraryId={libraryId}
            sourceType={details.sourceType}
            canManage={canTrigger}
            refreshToken={documentsRefreshToken}
            confluenceSpaces={details.confluenceSpaces}
            // #506 review, finding 7: the document count in the header comes from the library
            // itself, not from documentStore - without this it stays on whatever value was loaded
            // on mount even after an upload or delete changes it.
            onDocumentsChanged={() => void loadLibraryDetails(libraryId)}
          />
        )}
      </Box>

      {showSourceTab && details && (
        <Box
          role="tabpanel"
          id="library-tabpanel-quelle"
          aria-labelledby="library-tab-quelle"
          hidden={activeTab !== 'quelle'}
        >
          {/* #604 review, finding 1: source paths and the run protocol stay behind the MANAGER
              bar (canEditSource); the Umfang above them is visible to every reader. */}
          <LibrarySourceSection libraryId={libraryId} library={details} canEditSource={canEdit} />
        </Box>
      )}

      <Box
        role="tabpanel"
        id="library-tabpanel-metadaten"
        aria-labelledby="library-tab-metadaten"
        hidden={activeTab !== 'metadaten'}
      >
        <Stack>
          <PageSection
            title="Metadatenfelder"
            description="Eigene typisierte Felder dieser Bibliothek, ihre Wirkstellen und ihre Wertelisten."
          >
            <LibraryMetadataFieldsSection libraryId={libraryId} canManageSchema={canEdit} />
          </PageSection>
          {/* Die Extraktionseinstellungen liest erst MANAGER (GET .../metadata/extraction-settings)
              - für eine lesende Rolle bliebe hier nur eine Fehlermeldung stehen. */}
          {canEdit && (
            <PageSection
              title="Modellgestützte Extraktion"
              description="Ob das Sprachmodell leer gebliebene Felder ergänzt und freie Schlagworte vergibt — und wie gut die Extraktion diese Bibliothek beschreibt."
            >
              <MetadataExtractionSettingsSection libraryId={libraryId} canManage={canEdit} />
            </PageSection>
          )}
        </Stack>
      </Box>

      <Box
        role="tabpanel"
        id="library-tabpanel-freigaben"
        aria-labelledby="library-tab-freigaben"
        hidden={activeTab !== 'freigaben'}
      >
        <Stack>
          {canEdit ? (
            <AssetDistributionSection
              assetType="KNOWLEDGE_LIBRARY"
              assetId={libraryId}
              assetName={library.name}
              reach={library.reach}
              listed={library.listed}
              onSave={(listed) =>
                saveLibrary({ name: library.name, description: library.description, listed })
              }
              listedCap={details?.listedCap}
              capControl={
                // #797: only the system administration sets this - the owner only ever sees its
                // consequence, the locked switch and the 409 when a grant would exceed it.
                isSystemAdmin &&
                details &&
                details.sourceType !== 'UPLOAD' &&
                details.allAccountsGrantAllowed != null &&
                details.listedCap != null ? (
                  <ShareCapControl
                    allAccountsGrantAllowed={details.allAccountsGrantAllowed}
                    listedCap={details.listedCap}
                    saving={shareCapSaving}
                    error={shareCapError}
                    onSave={(allAccountsGrantAllowed, listedCap) =>
                      void handleSaveShareCap(allAccountsGrantAllowed, listedCap)
                    }
                    onDismissError={() => setShareCapError(null)}
                  />
                ) : null
              }
              grantsTypeSection={<LibraryExternalAccessSection libraryId={libraryId} />}
            />
          ) : (
            // Was eine lesende Rolle hier abrufen darf: in welchen Spaces die Bibliothek steht
            // und warum sie selbst sie sieht. Die Rechtevergabe bleibt den Verwaltenden.
            <>
              <PageSection
                title="Zuordnungen"
                description="Die Spaces, in denen diese Bibliothek als Datenquelle bereitsteht."
              >
                <AssetSpacesList
                  key={`spaces-${libraryId}`}
                  assetType="KNOWLEDGE_LIBRARY"
                  assetId={libraryId}
                  canManage={false}
                />
              </PageSection>
              <AssetAccessDerivationSection assetType="KNOWLEDGE_LIBRARY" assetId={libraryId} />
            </>
          )}

          {details && (
            <PageSection
              title="Diagnosesperre"
              description="Ob diese Bibliothek in einer Suchdiagnose im Rechtekontext einer anderen Person auftauchen darf."
            >
              <DiagnosticsLockControl
                locked={details.diagnosticsLocked ?? true}
                // #1278 review: myRole bypasses to OWNER for a system admin unconditionally
                // (LibraryResponse#myRole) - this dedicated field mirrors the backend's stricter
                // holdsIndependentOwnerRole instead, so an admin without an independent OWNER
                // grant never sees a button that is guaranteed to fail with 403.
                canToggle={details.diagnosticsLockToggleable ?? false}
                saving={diagnosticsLockSaving}
                error={diagnosticsLockError}
                onToggle={() => void handleToggleDiagnosticsLock()}
                onDismissError={() => setDiagnosticsLockError(null)}
              />
            </PageSection>
          )}
        </Stack>
      </Box>
    </Box>
  )
}
