import { useEffect, useRef, useState } from 'react'
import { Link as RouterLink, useNavigate, useParams, useSearchParams } from 'react-router'
import Alert from '@mui/material/Alert'
import AlertTitle from '@mui/material/AlertTitle'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import Chip from '@mui/material/Chip'
import FormControlLabel from '@mui/material/FormControlLabel'
import LinearProgress from '@mui/material/LinearProgress'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import Tab from '@mui/material/Tab'
import Tabs from '@mui/material/Tabs'
import Typography from '@mui/material/Typography'
import DataUsageIcon from '@mui/icons-material/DataUsage'
import HistoryIcon from '@mui/icons-material/History'
import PlayArrowIcon from '@mui/icons-material/PlayArrow'
import WarningAmberIcon from '@mui/icons-material/WarningAmber'
import { alpha } from '@mui/material/styles'
import type { AssetRole } from '../types/api'
import { confluenceSettingsOf } from '../utils/confluenceSource'
import { sourceRegistration } from '../components/library/sources/registry'
import { useAuthStore } from '../stores/authStore'
import { confirmAction } from '../stores/confirmStore'
import { useLibraryStore } from '../stores/libraryStore'
import { IDLE_RUN_STATE, useIndexingStore } from '../stores/indexingStore'
import { assetRoleLabel, formatFileSize } from '../utils/labels'
import { documentSourceTypeLabel } from '../components/library/sources/sourceLabels'
import AssetAccessDerivationSection from '../components/assets/AssetAccessDerivationSection'
import AssetDetailHeader, { HeaderFigure } from '../components/assets/AssetDetailHeader'
import AssetOwnerSection from '../components/assets/AssetOwnerSection'
import AssetSpacesSection from '../components/assets/AssetSpacesSection'
import { responsibleParty } from '../components/assets/assetTileData'
import { ErasingMark, PrivateMark } from '../components/assets/assetMarks'
import {
  PRIVATE_LIBRARY_ERASING_NOTE,
  PRIVATE_LIBRARY_ERASURE_CONSEQUENCE,
  PRIVATE_LIBRARY_NOTE,
  QUOTA_EXHAUSTED_LABEL,
  QUOTA_EXHAUSTED_REMEDY,
} from '../components/library/privateLibrary'
import { useMyPrivateStorage } from '../components/library/useMyPrivateStorage'
import { notify } from '../stores/notificationStore'
import { useAssetCatalogEntry } from '../components/assets/useAssetCatalogEntry'
import AssetGrantsSection from '../components/permissions/AssetGrantsSection'
import LibraryExternalAccessSection from '../components/library/LibraryExternalAccessSection'
import LibraryDocumentsSection from '../components/library/LibraryDocumentsSection'
import LibrarySourceSection from '../components/library/LibrarySourceSection'
import LibraryMetadataFieldsSection from '../components/metadata/LibraryMetadataFieldsSection'
import MetadataExtractionSettingsSection from '../components/metadata/MetadataExtractionSettingsSection'
import PageSection from '../components/PageSection'
import MetaBadge from '../components/MetaBadge'
import SuccessionStateNote from '../components/succession/SuccessionStateNote'
import { successionAwareMessage } from '../components/succession/successionConflict'
import { CATALOG_ROUTE } from '../routes'

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

function formatIndexedAt(indexedAt: string | null | undefined): string {
  if (!indexedAt) return '—'
  return new Date(indexedAt).toLocaleString('de-DE', { dateStyle: 'medium', timeStyle: 'short' })
}

/** The page's areas; the active one is shareable via the "tab" search param (URL as state). */
type LibraryDetailTab = 'dokumente' | 'quelle' | 'metadaten' | 'freigaben' | 'zuordnungen'

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
    case 'zuordnungen':
      return 'zuordnungen'
    default:
      return 'dokumente'
  }
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

interface ShareCapSwitchProps {
  label: string
  description: string
  checked: boolean
  onSave: (checked: boolean) => Promise<void>
}

/**
 * Die Obergrenze, die die Systemverwaltung einer Konnektorbibliothek setzt (#797, in der Gestalt
 * aus #1931): ob sie an „Alle Konten" freigegeben werden darf. Sichtbar und setzbar nur hier, nie
 * für den Eigentümer der Bibliothek: Der sieht die Wirkung (eine Freigabe über der Grenze antwortet
 * 409), nicht den Schalter.
 */
function ShareCapSwitch({ label, description, checked, onSave }: ShareCapSwitchProps) {
  const [draft, setDraft] = useState(checked)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const changed = draft !== checked

  async function handleSave() {
    setError(null)
    setSaving(true)
    try {
      await onSave(draft)
    } catch (err) {
      setError(
        err instanceof Error ? err.message : 'Freigabe-Obergrenze konnte nicht geändert werden',
      )
    } finally {
      setSaving(false)
    }
  }

  return (
    <Box sx={{ pt: 1, borderTop: '1px solid', borderColor: 'divider' }}>
      <Typography variant="subtitle2" component="h3">
        Obergrenze (Systemverwaltung)
      </Typography>
      <Typography variant="caption" component="p" sx={{ color: 'text.secondary', mb: 1 }}>
        {description}
      </Typography>
      <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
        <FormControlLabel
          control={<Checkbox checked={draft} onChange={(e) => setDraft(e.target.checked)} />}
          label={label}
        />
        <Button
          size="small"
          variant="outlined"
          disabled={saving || !changed}
          // Beide Hälften der Obergrenze stehen auf derselben Seite; der sichtbare Text ist
          // derselbe, der zugängliche Name nennt deshalb, welche Hälfte gespeichert wird.
          aria-label={`Obergrenze „${label}“ speichern`}
          onClick={() => void handleSave()}
        >
          {saving ? 'Wird gespeichert …' : 'Obergrenze speichern'}
        </Button>
      </Stack>
      {error && (
        <Alert severity="error" sx={{ mt: 1 }} onClose={() => setError(null)}>
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
  const [diagnosticsLockError, setDiagnosticsLockError] = useState<string | null>(null)
  const [diagnosticsLockSaving, setDiagnosticsLockSaving] = useState(false)
  const [searchParams] = useSearchParams()
  const catalog = useAssetCatalogEntry('KNOWLEDGE_LIBRARY', libraryId ?? '')
  const [associationsVersion, setAssociationsVersion] = useState(0)

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
  // Only its owner ever reads a private library: no rights, transfer, external access or cap.
  const privateLibrary = Boolean(library?.privateLibrary)
  // A private library marked for erasure runs no more and takes in nothing; nothing is changed.
  const erasureRequestedAt = details?.erasureRequestedAt ?? null
  const erasing = erasureRequestedAt != null
  const roleGrantsEdit = canEditLibrary(library?.myRole)
  const roleGrantsDelete = canDeleteLibrary(library?.myRole)
  const canEdit = roleGrantsEdit || isSystemAdmin
  const canDelete = roleGrantsDelete || isSystemAdmin
  const isAdministrativeOverride = isSystemAdmin && !roleGrantsEdit
  // The list holds only what the caller may read, without the administrative bypass: a system
  // administrator without a grant may open the page, but cannot associate the library anywhere.
  const mayUseInSpace = (!isSystemAdmin || listEntry !== undefined) && !erasing
  const canTrigger = canManageDocuments(library?.myRole) || isSystemAdmin
  const mayChange = canEdit && !erasing
  const mayManageDocuments = canTrigger && !erasing
  const erasureNoticeRef = useRef<HTMLDivElement>(null)
  // Set by a 202: the menu that held „Sofort löschen" is gone, so the focus moves to the notice.
  const focusErasureNoticeRef = useRef(false)
  const [eraseBusy, setEraseBusy] = useState(false)
  const newestRunCategory = useIndexingStore((s) =>
    libraryId ? s.runsByLibrary[libraryId]?.failureCategory : undefined,
  )
  const quotaExhausted = privateLibrary && !erasing && newestRunCategory === 'QUOTA_EXHAUSTED'
  const ownsPrivateLibrary = privateLibrary && library?.myRole === 'OWNER'
  const [storageRefreshToken, setStorageRefreshToken] = useState(0)
  const privateStorage = useMyPrivateStorage(ownsPrivateLibrary, storageRefreshToken)

  useEffect(() => {
    if (focusErasureNoticeRef.current && erasing) {
      focusErasureNoticeRef.current = false
      erasureNoticeRef.current?.focus()
    }
  }, [erasing])

  // The connector-only concerns (status polling, trigger actions, the "Indizierung" area) hang
  // off the details' sourceType; a plain UPLOAD library has none of them.
  const connectorSourceType = details && details.sourceType !== 'UPLOAD' ? details.sourceType : null
  const connectorConfiguration = sourceRegistration(connectorSourceType)?.configuration ?? null

  // #1939: every role sees every area; a reader simply finds less inside it. Only an UPLOAD
  // library genuinely has no source area.
  const showSourceTab = connectorSourceType != null

  // #797: only the system administration sets the ceiling - the owner only ever sees its
  // consequence, the locked switch and the 409 when a grant would exceed it. An UPLOAD library
  // carries none.
  const shareCapVisible =
    isSystemAdmin &&
    details != null &&
    details.sourceType !== 'UPLOAD' &&
    details.allAccountsGrantAllowed != null
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
      setStorageRefreshToken((token) => token + 1)
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

  /** The PUT replaces name and description as a whole. */
  async function saveLibrary(fields: { name: string; description: string | null | undefined }) {
    if (!libraryId) return
    await updateExistingLibrary(libraryId, {
      name: fields.name.trim(),
      description: fields.description?.trim() || undefined,
      // Bewusst kein Quellkonfigurationsfeld gesetzt: das Backend lässt die gespeicherte
      // Konfiguration unverändert, solange keines der sourcePath/sourceUrl/sourceProxy/
      // sourceCredentials/sourceInsecureSsl-Felder in der Anfrage vorhanden ist (ADR-0018). Das
      // Bearbeiten der Quellkonfiguration selbst laeuft ueber EditLibrarySourceDialog weiter
      // unten in dieser Datei (#516) - dieses Stammdaten-Formular hier ruehrt sie nicht an.
      sourceInsecureSsl: null,
    })
  }

  /** Nach einem Eigentumswechsel: Liste und Detail neu, damit Name und Rolle wieder stimmen. */
  async function reloadLibrary() {
    if (!libraryId) return
    await Promise.all([loadLibraries(), loadLibraryDetails(libraryId)])
  }

  /**
   * Name and description from the head. The rejection is thrown on, not swallowed: the editor in
   * the head shows it next to the fields the draft still holds.
   */
  async function saveHeadline(nextName: string, nextDescription: string) {
    if (!library) return
    try {
      await saveLibrary({ name: nextName, description: nextDescription })
    } catch (err) {
      throw new Error(successionAwareMessage(err, 'Aktualisierung fehlgeschlagen'), { cause: err })
    }
  }

  /**
   * „Sofort löschen" of a private library: 204 erased it, 202 marked it while a run still ends -
   * the page then stays and shows the state, without promising when the erasure completes.
   */
  async function handleErase() {
    if (!libraryId || !library || eraseBusy) return
    const confirmed = await confirmAction({
      question: `„${library.name}“ sofort löschen?`,
      consequence: PRIVATE_LIBRARY_ERASURE_CONSEQUENCE,
      confirmLabel: 'Endgültig löschen',
      tone: 'danger',
    })
    if (!confirmed) return
    setLocalError(null)
    setEraseBusy(true)
    // armed before the request: the store reloads the marked library before it answers
    focusErasureNoticeRef.current = true
    try {
      const outcome = await deleteExistingLibrary(libraryId)
      if (outcome === 'ERASURE_PENDING') {
        // also when reloading the marked library failed and the page cannot show its state
        notify(
          `„${library.name}“ ist zur Löschung vorgemerkt. Die Löschung schließt ab, sobald die laufende Indexierung beendet ist.`,
          'info',
        )
        return
      }
      focusErasureNoticeRef.current = false
      notify(`„${library.name}“ ist gelöscht.`, 'success')
      navigate(CATALOG_ROUTE)
    } catch (err) {
      focusErasureNoticeRef.current = false
      setLocalError(err instanceof Error ? err.message : 'Löschen fehlgeschlagen')
    } finally {
      setEraseBusy(false)
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
      navigate(CATALOG_ROUTE)
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

  /** Eine Ablehnung wird weitergereicht - sie gehört in den Schalter, der sie ausgelöst hat. */
  async function handleSaveShareCap(allAccountsGrantAllowed: boolean) {
    if (!libraryId) return
    await setLibraryShareCap(libraryId, { allAccountsGrantAllowed })
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

  const isRssFeedRun = Boolean(sourceRegistration(connectorSourceType)?.runCountsEntries)
  const runFailedSuffix =
    run.documentsFailed > 0 ? `, davon ${run.documentsFailed} fehlgeschlagen` : ''

  const tabs: Array<{ value: LibraryDetailTab; label: string }> = [
    { value: 'dokumente', label: 'Dokumente' },
    ...(showSourceTab ? ([{ value: 'quelle', label: 'Quelle' }] as const) : []),
    { value: 'metadaten', label: 'Metadaten' },
    { value: 'freigaben', label: 'Freigaben' },
    { value: 'zuordnungen', label: 'Zuordnungen' },
  ]

  function associationsChanged() {
    catalog.reload()
    setAssociationsVersion((version) => version + 1)
  }

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <AssetDetailHeader
        assetType="KNOWLEDGE_LIBRARY"
        assetId={libraryId}
        name={library.name}
        description={library.description}
        isPublic={library.reach.allAccounts}
        badges={
          <>
            {privateLibrary && <PrivateMark />}
            {erasing && <ErasingMark />}
            {details && <MetaBadge>{documentSourceTypeLabel(details.sourceType)}</MetaBadge>}
            <MetaBadge accent>{assetRoleLabel(library.myRole)}</MetaBadge>
          </>
        }
        administrative={isAdministrativeOverride}
        headline={{
          idPrefix: 'library-detail',
          nameLabel: 'Name der Bibliothek',
          editLabel: 'Name und Beschreibung bearbeiten',
          canEdit: mayChange,
          onSave: saveHeadline,
        }}
        extent={`${(library.documentCount ?? 0).toLocaleString('de-DE')} ${
          (library.documentCount ?? 0) === 1 ? 'Dokument' : 'Dokumente'
        }`}
        // storageQuotaBytes/storageUsedBytes reach a caller with at least MANAGER only.
        figures={
          <>
            {details?.storageQuotaBytes != null && details.storageUsedBytes != null && (
              <HeaderFigure icon={<DataUsageIcon sx={{ fontSize: 16 }} />}>
                {formatFileSize(details.storageUsedBytes)} von{' '}
                {formatFileSize(details.storageQuotaBytes)} Speicherkontingent belegt
              </HeaderFigure>
            )}
            {privateStorage && (
              <HeaderFigure icon={<DataUsageIcon sx={{ fontSize: 16 }} />}>
                <span data-testid="private-storage-figure">
                  {privateStorage.quotaBytes > 0
                    ? `${formatFileSize(privateStorage.usedBytes)} von ${formatFileSize(privateStorage.quotaBytes)} in Ihren privaten Bibliotheken belegt`
                    : `${formatFileSize(privateStorage.usedBytes)} in Ihren privaten Bibliotheken belegt (unbegrenzt)`}
                </span>
              </HeaderFigure>
            )}
            {connectorSourceType && canTrigger && !isRunning && run.status !== 'IDLE' && (
              <HeaderFigure
                icon={
                  <HistoryIcon
                    sx={{
                      fontSize: 16,
                      color: run.status === 'COMPLETED' ? 'success.main' : 'error.main',
                    }}
                  />
                }
              >
                Letzter Lauf: {run.status === 'COMPLETED' ? 'Abgeschlossen' : 'Fehlgeschlagen'}
                {' · '}
                {isRssFeedRun
                  ? `${run.totalDocuments} Feed-Einträge, ${run.documentsSkipped} übersprungen, ${run.documentCount} indiziert (${run.documentsIndexedTotal} Dokumente insgesamt)${runFailedSuffix}`
                  : `Dokumente: ${run.documentCount} verarbeitet${run.documentsSkipped > 0 ? ` (${run.documentsSkipped} übersprungen)` : ''}${runFailedSuffix}`}
                {run.timestamp ? ` · ${formatIndexedAt(run.timestamp)}` : ''}
              </HeaderFigure>
            )}
          </>
        }
        spaceCount={catalog.entry?.spaceCount}
        responsible={responsibleParty({
          ownerType: library.ownerType,
          ownerLabel: library.ownerName,
          succession: details?.succession ?? library.succession,
        })}
        // An upload library has no runs, so no lastIndexedAt; its date is then its last change.
        updatedAt={listEntry?.lastIndexedAt ?? library.updatedAt}
        favorite={catalog.entry?.favorite}
        onFavoriteChange={catalog.setFavorite}
        mayUseInSpace={mayUseInSpace}
        onAssociated={associationsChanged}
        onDelete={
          !canDelete || erasing
            ? undefined
            : privateLibrary
              ? () => void handleErase()
              : () => void handleDelete()
        }
        deleteLabel={privateLibrary ? 'Sofort löschen' : undefined}
        deleteDisabled={eraseBusy}
        actions={
          connectorSourceType &&
          mayManageDocuments && (
            <>
              <Button
                variant="contained"
                size="small"
                startIcon={<PlayArrowIcon />}
                onClick={() => void triggerIndexing(libraryId, connectorSourceType)}
                disabled={isRunning}
              >
                {isRunning ? 'Indizierung läuft …' : 'Jetzt indizieren'}
              </Button>
              {/* ADR-0023, Entscheidung 4 (#1139): "Jetzt indizieren" follows the library's state
                  (incremental between two full runs); the full reconciliation can be forced. */}
              {connectorConfiguration?.fullSyncRhythm && (
                <Button
                  variant="outlined"
                  size="small"
                  onClick={() => void triggerIndexing(libraryId, connectorSourceType, 'FULL')}
                  disabled={isRunning}
                >
                  Vollabgleich starten
                </Button>
              )}
            </>
          )
        }
        // ADR-0036, Entscheidung 6: state and addressee for every reader - no date, no former
        // owner, no reason.
        note={
          <>
            <SuccessionStateNote succession={details?.succession} />
            {erasureRequestedAt && (
              <Alert
                ref={erasureNoticeRef}
                tabIndex={-1}
                role="status"
                severity="error"
                icon={false}
                sx={{ mt: 1.5 }}
                data-testid="library-erasure-notice"
              >
                <AlertTitle>
                  Wird gelöscht – vorgemerkt am {formatIndexedAt(erasureRequestedAt)}
                </AlertTitle>
                {PRIVATE_LIBRARY_ERASING_NOTE}
              </Alert>
            )}
            {quotaExhausted && (
              <Alert
                severity="warning"
                sx={{ mt: 1.5 }}
                data-testid="private-quota-exhausted-notice"
              >
                <AlertTitle>Letzter Lauf {QUOTA_EXHAUSTED_LABEL}</AlertTitle>
                {QUOTA_EXHAUSTED_REMEDY}
              </Alert>
            )}
          </>
        }
      >
        {/* #1939: Der Umfang steht im Kopf nur noch als Kurzzeile mit Sprung in den Reiter
            „Quelle"; dort steht er vollständig, für jede Rolle. Die Warnung über einen
            unvollständig gelesenen Umfang bleibt dagegen hier: Sie betrifft den Bestand, den
            jede Ansicht der Seite zeigt, nicht die Einstellung dahinter. */}
        {details && connectorConfiguration?.scopeHero && (
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
              {connectorConfiguration.scopeHero.summary(details)}
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
            {run.unlistedScopeKeys.length > 0 && (
              <Stack
                direction="row"
                spacing={1}
                role="note"
                data-testid={connectorConfiguration.scopeHero.unlistedTestId}
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
                  {connectorConfiguration.scopeHero.unlistedWarning(run.unlistedScopeKeys, details)}
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
      </AssetDetailHeader>

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
            canManage={mayManageDocuments}
            refreshToken={documentsRefreshToken}
            confluenceSpaces={confluenceSettingsOf(details).spaces}
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
          <LibrarySourceSection
            libraryId={libraryId}
            library={details}
            canEditSource={canEdit}
            erasing={erasing}
          />
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
            <LibraryMetadataFieldsSection libraryId={libraryId} canManageSchema={mayChange} />
          </PageSection>
          {/* Die Extraktionseinstellungen liest erst MANAGER (GET .../metadata/extraction-settings)
              - für eine lesende Rolle bliebe hier nur eine Fehlermeldung stehen. */}
          {canEdit && (
            <PageSection
              title="Modellgestützte Extraktion"
              description="Ob das Sprachmodell leer gebliebene Felder ergänzt und freie Schlagworte vergibt — und wie gut die Extraktion diese Bibliothek beschreibt."
            >
              <MetadataExtractionSettingsSection libraryId={libraryId} canManage={mayChange} />
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
        {/* Die Reihenfolge des Zielentwurfs (#1927): Eigentümer · Berechtigungen · Externer
            Zugang · Diagnosesperre · Herleitung. Jeder Abschnitt speichert für sich;
            es gibt keinen gemeinsamen „Speichern"-Knopf über Abschnitte hinweg. */}
        <Stack>
          <AssetOwnerSection
            assetType="KNOWLEDGE_LIBRARY"
            assetId={libraryId}
            ownerType={library.ownerType}
            ownerName={library.ownerName}
            // Übergeben darf nur, wer das Eigentum hält - myRole trägt für eine Systemverwaltung
            // ohnehin OWNER, und genau die darf es laut Endpunkt auch.
            canTransfer={library.myRole === 'OWNER' && !privateLibrary}
            onTransferred={() => reloadLibrary()}
          />

          {privateLibrary && (
            <Alert severity="info" sx={{ mb: 4 }} data-testid="library-private-note">
              {PRIVATE_LIBRARY_NOTE} Sie läuft über Ihr verbundenes Konto und lässt sich weder
              freigeben noch übertragen oder über einen Fremdzugang erreichen — auch nicht durch die
              Systemverwaltung.
            </Alert>
          )}

          {canEdit && !privateLibrary && (
            <>
              <AssetGrantsSection
                assetType="KNOWLEDGE_LIBRARY"
                assetId={library.id}
                capControl={
                  shareCapVisible ? (
                    <ShareCapSwitch
                      label="Freigabe an Alle erlaubt"
                      description="Legt fest, ob diese Konnektorbibliothek überhaupt an alle Konten freigegeben werden darf. Wird die Erlaubnis entzogen, wird eine bereits bestehende Freigabe sofort zurückgenommen."
                      checked={details?.allAccountsGrantAllowed ?? true}
                      onSave={(allowed) => handleSaveShareCap(allowed)}
                    />
                  ) : null
                }
              />

              <PageSection
                title="Externer Zugang"
                description="Ob diese Bibliothek über einen Fremdzugang außerhalb der Anmeldung erreichbar ist."
              >
                <LibraryExternalAccessSection libraryId={libraryId} />
              </PageSection>
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

          {!privateLibrary && (
            <AssetAccessDerivationSection assetType="KNOWLEDGE_LIBRARY" assetId={libraryId} />
          )}
        </Stack>
      </Box>

      <Box
        role="tabpanel"
        id="library-tabpanel-zuordnungen"
        aria-labelledby="library-tab-zuordnungen"
        hidden={activeTab !== 'zuordnungen'}
      >
        {activeTab === 'zuordnungen' && (
          <AssetSpacesSection
            assetType="KNOWLEDGE_LIBRARY"
            assetId={libraryId}
            name={library.name}
            canManage={mayChange}
            mayUseInSpace={mayUseInSpace}
            refreshToken={associationsVersion}
            onChanged={() => catalog.reload()}
          />
        )}
      </Box>
    </Box>
  )
}
