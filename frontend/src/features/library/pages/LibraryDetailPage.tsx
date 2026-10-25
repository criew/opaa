import { useEffect, useRef, useState } from 'react'
import { useParams, useSearchParams } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import PlayArrowIcon from '@mui/icons-material/PlayArrow'
import UploadIcon from '@mui/icons-material/FileUploadOutlined'
import type { IndexingRunResponse, LibrarySchedule } from '@/shared/api/types'
import { scheduleSummary } from '../components/setup/scheduleSummary'
import { scheduleValuesFrom, type ConfluenceFullSyncRhythm } from '../utils/librarySchedule'
import { confluenceSettingsOf } from '../utils/confluenceSource'
import { sourceRegistration } from '../components/sources/registry'
import { useAuthStore } from '@/shared/session/authStore'
import { useLibraryStore } from '../stores/libraryStore'
import { IDLE_RUN_STATE, useIndexingStore } from '../stores/indexingStore'
import { useDocumentStore } from '../stores/documentStore'
import { assetRoleLabel, formatFileSize } from '@/shared/utils/labels'
import { documentSourceTypeShortLabel } from '../components/sources/sourceLabels'
import { AssetDetailHeader, HeaderFigure } from '@/features/asset'
import { responsibleParty } from '@/features/asset'
import { useMyPrivateStorage } from '../components/useMyPrivateStorage'
import { useAssetCatalogEntry } from '@/features/asset'
import { AssetSpacesSection } from '@/features/asset'
import LibraryDocumentsSection from '../components/LibraryDocumentsSection'
import LibrarySourceSection from '../components/LibrarySourceSection'
import { useFeatureEnabled } from '@/shared/session/useFeatureEnabled'
import MetaBadge from '@/shared/ui/MetaBadge'
import { successionAwareMessage } from '@/features/succession'
import {
  canDeleteLibrary,
  canEditLibrary,
  canManageDocuments,
  resolveTab,
} from '../components/libraryDetailAccess'
import type { LibraryDetailTab } from '../components/libraryDetailAccess'
import { useLibraryDeletion } from '../components/useLibraryDeletion'
import LibraryDetailTabBar from '../components/LibraryDetailTabBar'
import LibraryMetadataTab from '../components/LibraryMetadataTab'
import LibrarySharingTab from '../components/LibrarySharingTab'
import LibraryRunHistory from '../components/LibraryRunHistory'
import LibraryStatusBand, { BandState, type BandField } from '../components/LibraryStatusBand'
import {
  connectorState,
  formatShortDate,
  runState,
  uploadState,
  type LibraryState,
} from '../components/libraryState'
import { hasScopeChoice, scopeShort, unreadableNames } from '../components/scopeFacts'
import type { RowStatus } from '../components/setup/setupTypes'
import {
  LIBRARY_QUOTA_EXHAUSTED_REMEDY,
  PRIVATE_LIBRARY_ERASING_NOTE,
  QUOTA_EXHAUSTED_REMEDY,
} from '../components/privateLibrary'

// A stable empty history, so the store selector never returns a fresh array identity.
const NO_RUNS: IndexingRunResponse[] = []

/**
 * The connection's last known state, from the newest finished run (D10): „Zustand · zuletzt
 * Datum“ in every case, also while a newer run is still going.
 */
function connectionStatusOf(runs: IndexingRunResponse[], signsIn: boolean): RowStatus | null {
  const run = runs.find((entry) => entry.status !== 'RUNNING')
  if (!run) return null
  const date = formatShortDate(run.completedAt ?? run.startedAt)
  const { word } = runState(run)
  if (word === 'Anmeldung abgelehnt' || word === 'Zustimmung abgelaufen') {
    return { tone: 'error', label: `${word} · ${date}` }
  }
  if (run.status === 'FAILED') {
    return signsIn ? null : { tone: 'error', label: `nicht erreichbar · ${date}` }
  }
  return { tone: 'success', label: `${signsIn ? 'Anmeldung ok' : 'erreichbar'} · zuletzt ${date}` }
}

/** „täglich 06:00 · 25.10.2026“ - rhythm first, then the date of the next planned run. */
function nextRunLabel(
  schedule: LibrarySchedule | null | undefined,
  rhythm: ConfluenceFullSyncRhythm | undefined,
): string {
  if (!schedule || schedule.frequency === 'DISABLED') return 'kein Zeitplan'
  const base = scheduleSummary(scheduleValuesFrom(schedule, rhythm)).replace(
    / · Vollabgleich.*$/,
    '',
  )
  const next = formatShortDate(schedule.nextRunAt)
  return next ? `${base} · ${next}` : base
}

export default function LibraryDetailPage() {
  const { libraryId } = useParams()
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')

  const listEntry = useLibraryStore((s) => s.libraries.find((l) => l.id === libraryId))
  const details = useLibraryStore((s) => (libraryId ? s.libraryDetails[libraryId] : undefined))
  const loadLibraries = useLibraryStore((s) => s.loadLibraries)
  const loadLibraryDetails = useLibraryStore((s) => s.loadLibraryDetails)
  const updateExistingLibrary = useLibraryStore((s) => s.updateExistingLibrary)
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
  const { erasureNoticeRef, eraseBusy, handleErase, handleDelete } = useLibraryDeletion({
    libraryId,
    library,
    details,
    erasing,
    setLocalError,
  })
  const ownsPrivateLibrary = privateLibrary && library?.myRole === 'OWNER'
  const [storageRefreshToken, setStorageRefreshToken] = useState(0)
  const privateStorage = useMyPrivateStorage(ownsPrivateLibrary, storageRefreshToken)

  // The connector-only concerns (status polling, trigger actions, the "Indizierung" area) hang
  // off the details' sourceType; a plain UPLOAD library has none of them.
  const connectorSourceType = details && details.sourceType !== 'UPLOAD' ? details.sourceType : null
  const connectorConfiguration = sourceRegistration(connectorSourceType)?.configuration ?? null

  // Every role sees every area; a reader simply finds less inside it. Only an UPLOAD
  // library genuinely has no source area. The list entry decides it while the details are
  // missing, so a failed details load keeps the area and explains itself there.
  const knownSourceType = details?.sourceType ?? listEntry?.sourceType
  const showSourceTab = knownSourceType != null && knownSourceType !== 'UPLOAD'
  const showMetadataTab = useFeatureEnabled('metadata')
  const externalAccessEnabled = useFeatureEnabled('externalAccess')

  // Only the system administration sets the ceiling - the owner only ever sees its
  // consequence, the locked switch and the 409 when a grant would exceed it. An UPLOAD library
  // carries none.
  const shareCapVisible =
    isSystemAdmin &&
    details != null &&
    details.sourceType !== 'UPLOAD' &&
    details.allAccountsGrantAllowed != null
  // The run list is readable from MANAGER on, like the rows of „Quelle“ behind it.
  const showRunsTab = showSourceTab && canEdit
  const activeTab: LibraryDetailTab = resolveTab(
    searchParams.get('tab'),
    showSourceTab,
    showMetadataTab,
    showRunsTab,
  )

  // Status polling lives at page level, not inside the (hideable) "Indizierung" area: a running
  // indexing stays visible in the page head no matter which area is active (guidelines 5.7).
  const run = useIndexingStore((s) =>
    libraryId ? (s.runsByLibrary[libraryId] ?? IDLE_RUN_STATE) : IDLE_RUN_STATE,
  )
  const triggerIndexing = useIndexingStore((s) => s.triggerIndexing)
  const loadStatus = useIndexingStore((s) => s.loadStatus)
  const stopPolling = useIndexingStore((s) => s.stopPolling)
  const retryStatusPolling = useIndexingStore((s) => s.retryStatusPolling)
  const runs = useIndexingStore((s) =>
    libraryId ? (s.runHistoryByLibrary[libraryId] ?? NO_RUNS) : NO_RUNS,
  )
  const loadRunHistory = useIndexingStore((s) => s.loadRunHistory)
  // whether the run history has arrived: until then the connection row claims nothing
  const runsLoaded = useIndexingStore((s) =>
    libraryId ? s.runHistoryByLibrary[libraryId] !== undefined : false,
  )
  const uploadErrors = useDocumentStore((s) => s.uploadErrors)
  const isUploading = useDocumentStore((s) => s.isUploading)
  const loadedDocuments = useDocumentStore((s) =>
    libraryId ? s.documentsByLibrary[libraryId] : undefined,
  )
  const newestRun = runs[0] ?? null
  const uploadPickerRef = useRef<{ open: () => void; openFolder: () => void }>(null)

  // The head, the run table and the run detail read one history (D3).
  useEffect(() => {
    if (!libraryId || !connectorSourceType || !canEdit) return
    void loadRunHistory(libraryId)
  }, [libraryId, connectorSourceType, canEdit, loadRunHistory])

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
      void loadRunHistory(libraryId)
      void loadLibraryDetails(libraryId)
      setDocumentsRefreshToken((token) => token + 1)
      setStorageRefreshToken((token) => token + 1)
    }
    wasRunningRef.current = isRunning
  }, [isRunning, libraryId, loadLibraryDetails, loadRunHistory])

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
      // Deliberately no source configuration field set: the backend leaves the stored
      // configuration unchanged as long as none of the sourcePath/sourceUrl/
      // sourceCredentials fields is present in the request (ADR-0018). Editing
      // the source configuration itself goes through EditLibrarySourceDialog further
      // down in this file - this master data form does not touch it.
    })
  }

  /** After a change of ownership: list and detail anew, so that name and role are right again. */
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

  // PUT .../diagnostics-lock takes the desired end state, not a toggle - this always
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

  /** A rejection is passed on - it belongs in the switch that triggered it. */
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

  const tabs: Array<{ value: LibraryDetailTab; label: string }> = [
    { value: 'dokumente', label: 'Dokumente' },
    ...(showRunsTab ? ([{ value: 'verlauf', label: 'Verlauf' }] as const) : []),
    ...(showSourceTab ? ([{ value: 'quelle', label: 'Quelle' }] as const) : []),
    ...(showMetadataTab ? ([{ value: 'metadaten', label: 'Metadaten' }] as const) : []),
    { value: 'freigaben', label: 'Freigaben' },
    { value: 'zuordnungen', label: 'Zuordnungen' },
  ]

  const successionOpen = Boolean(details?.succession ?? library.succession)
  const quotaRemedy = privateLibrary ? QUOTA_EXHAUSTED_REMEDY : LIBRARY_QUOTA_EXHAUSTED_REMEDY
  const unreadable =
    details && connectorSourceType ? unreadableNames(details, run.unlistedScopeKeys, newestRun) : []
  const isUpload = (details?.sourceType ?? listEntry?.sourceType) === 'UPLOAD'
  const documentsOnPage = loadedDocuments ?? []
  // the newest of the loaded documents, or the library's own last change
  const newestDocument = [...documentsOnPage].sort((a, b) =>
    (b.indexedAt ?? '').localeCompare(a.indexedAt ?? ''),
  )[0]
  const lastUpload =
    [library.updatedAt, newestDocument?.indexedAt]
      .filter((value): value is string => Boolean(value))
      .sort()
      .at(-1) ?? null
  const quotaFull =
    uploadErrors.some((message) => /kontingent/i.test(message)) ||
    (details?.storageQuotaBytes != null &&
      details.storageUsedBytes != null &&
      details.storageUsedBytes >= details.storageQuotaBytes)
  const libraryState: LibraryState = isUpload
    ? uploadState({
        erasureRequestedAt,
        successionOpen,
        quotaFull,
        // the loaded page of the list: the backend reports no count per status yet
        processing: isUploading || documentsOnPage.some((d) => d.status === 'PENDING'),
        failed: documentsOnPage.some((d) => d.status === 'FAILED'),
        documentCount: library.documentCount ?? 0,
        lastUpload,
      })
    : connectorState({
        erasureRequestedAt,
        successionOpen,
        sourceBlock: details?.sourceBlock ?? null,
        newestRun: newestRun?.status === 'RUNNING' && !isRunning ? (runs[1] ?? null) : newestRun,
        status: {
          status: run.status,
          timestamp: run.timestamp,
          documentsFailed: run.documentsFailed,
          unlistedScopeKeys: run.unlistedScopeKeys,
          failureCategory: run.failureCategory,
          percent: isRunning && run.totalDocuments > 0 ? runProgressPercent : null,
        },
        lastScheduledRunsFailed: Boolean(details?.lastScheduledRunsFailed),
      })
  // a band link never points at the tab that is open: the place is already in view
  const linkTo = (tab: LibraryDetailTab, label: string) =>
    activeTab === tab ? null : { label, to: tabSearch(tab) }
  const stateLink = (() => {
    if (isRunning && run.statusUnknown) {
      return { label: 'Erneut versuchen', onClick: () => retryStatusPolling(libraryId) }
    }
    switch (libraryState.target) {
      case 'verlauf':
        return showRunsTab ? linkTo('verlauf', 'Verlauf ›') : null
      case 'verbindung':
        // the connection row is a manager's; a reader has no place to go there
        return showSourceTab && canEdit ? linkTo('quelle', 'Verbindung ›') : null
      case 'freigaben':
        return linkTo('freigaben', 'Freigaben ›')
      case 'dokumente':
        return linkTo('dokumente', 'Dokumente ›')
      default:
        return null
    }
  })()
  const stateDetail = isRunning && run.statusUnknown ? 'Stand unbekannt' : libraryState.extra
  const stateField: BandField = {
    label: 'Stand',
    value: <BandState tone={libraryState.tone} word={libraryState.word} />,
    detail: stateDetail,
    title: erasing
      ? PRIVATE_LIBRARY_ERASING_NOTE
      : [libraryState.word, stateDetail].filter(Boolean).join(' · '),
    link: stateLink,
  }
  const scopeText = details ? scopeShort(details) : ''
  const bandFields: [BandField, BandField, BandField] =
    isUpload || !details
      ? [
          stateField,
          {
            label: 'Speicher',
            value:
              details?.storageQuotaBytes != null && details.storageUsedBytes != null
                ? `${formatFileSize(details.storageUsedBytes)} von ${formatFileSize(details.storageQuotaBytes)}`
                : 'keine Angabe',
          },
          {
            label: 'Neuestes',
            title: newestDocument?.fileName,
            value: newestDocument?.fileName ?? 'noch kein Dokument',
          },
        ]
      : [
          stateField,
          {
            label: 'Umfang',
            // what the last run could not read comes first, so it is never the part cut off
            value:
              unreadable.length > 0
                ? `${unreadable.length} nicht lesbar · ${scopeText}`
                : scopeText,
            title:
              unreadable.length > 0
                ? `${unreadable.length} nicht lesbar · ${scopeText}`
                : scopeText,
            link: hasScopeChoice(details) ? linkTo('quelle', 'Quelle ›') : null,
          },
          {
            label: 'Nächster Lauf',
            value: nextRunLabel(
              details.schedule,
              connectorConfiguration?.fullSyncRhythm
                ? {
                    intervalDays:
                      (details.sourceSettings?.fullSyncIntervalDays as number | null | undefined) ??
                      null,
                    defaultDays: details.fullSyncIntervalDefaultDays ?? null,
                  }
                : undefined,
            ),
            link: canEdit ? linkTo('quelle', 'Zeitplan ›') : null,
          },
        ]

  function associationsChanged() {
    catalog.reload()
    setAssociationsVersion((version) => version + 1)
  }

  return (
    <Box
      sx={{
        flexGrow: 1,
        px: { xs: 2.5, md: 5 },
        pb: { xs: 2.5, md: 5 },
        pt: { xs: 2, md: 3 },
        overflowY: 'auto',
      }}
    >
      <AssetDetailHeader
        assetType="KNOWLEDGE_LIBRARY"
        assetId={libraryId}
        name={library.name}
        description={library.description}
        isPublic={library.reach.allAccounts}
        isPrivate={privateLibrary}
        badges={
          <>
            {/* „privat“ rides on the type chip; the erasure is the state in the band */}
            {/* the short form of the catalog tile: one name, no double label (P19) */}
            {details && <MetaBadge>{documentSourceTypeShortLabel(details.sourceType)}</MetaBadge>}
            <MetaBadge>{assetRoleLabel(library.myRole)}</MetaBadge>
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
        spacesTo={tabSearch('zuordnungen')}
        responsibleTo={tabSearch('freigaben')}
        band={
          <Box ref={erasureNoticeRef} tabIndex={-1} sx={{ outline: 'none' }}>
            <LibraryStatusBand label="Stand dieser Bibliothek" fields={bandFields} />
          </Box>
        }
        // storageQuotaBytes/storageUsedBytes reach a caller with at least MANAGER only; an upload
        // library shows its own storage in the band.
        figures={
          <>
            {privateStorage && (
              <HeaderFigure>
                <span data-testid="private-storage-figure">
                  {privateStorage.quotaBytes > 0
                    ? `${formatFileSize(privateStorage.usedBytes)} von ${formatFileSize(privateStorage.quotaBytes)}`
                    : formatFileSize(privateStorage.usedBytes)}
                </span>
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
        // one main action at the same place for every library: run the source, or upload; a
        // running run shows its progress in the band, not on the button (D7)
        // folders go in from „⋯“ as well, so an empty upload library offers them too
        menuItems={
          isUpload && mayManageDocuments
            ? [{ label: 'Ordner hochladen', onClick: () => uploadPickerRef.current?.openFolder() }]
            : undefined
        }
        primaryAction={
          connectorSourceType && mayManageDocuments
            ? {
                label: 'Jetzt indizieren',
                icon: <PlayArrowIcon />,
                onClick: () => void triggerIndexing(libraryId, connectorSourceType),
                disabled: isRunning,
              }
            : isUpload && mayManageDocuments
              ? {
                  label: 'Dateien hochladen',
                  icon: <UploadIcon />,
                  onClick: () => uploadPickerRef.current?.open(),
                }
              : undefined
        }
      />

      {localError && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setLocalError(null)}>
          {localError}
        </Alert>
      )}
      {/* A failed GET /libraries/{id} while the list entry is cached leaves the typed areas
          below empty; this notice explains it and offers the reload. */}
      {!details && storeError && (
        <Alert
          severity="error"
          sx={{ mb: 2 }}
          action={
            <Button color="inherit" size="small" onClick={() => void loadLibraryDetails(libraryId)}>
              Erneut versuchen
            </Button>
          }
        >
          Die Angaben zu dieser Bibliothek konnten nicht vollständig geladen werden. Versuchen Sie
          es in einem Moment erneut.
        </Alert>
      )}

      <LibraryDetailTabBar activeTab={activeTab} tabs={tabs} tabSearch={tabSearch} />

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
            // The document count in the header comes from the library
            // itself, not from documentStore - without this it stays on whatever value was loaded
            // on mount even after an upload or delete changes it.
            onDocumentsChanged={() => void loadLibraryDetails(libraryId)}
            uploadPickerRef={uploadPickerRef}
            onIndexNow={
              connectorSourceType && mayManageDocuments && !isRunning
                ? () => void triggerIndexing(libraryId, connectorSourceType)
                : undefined
            }
          />
        )}
      </Box>

      {showRunsTab && connectorSourceType && (
        <Box
          role="tabpanel"
          id="library-tabpanel-verlauf"
          aria-labelledby="library-tab-verlauf"
          hidden={activeTab !== 'verlauf'}
        >
          <LibraryRunHistory
            runs={runs}
            showMode={Boolean(connectorConfiguration?.fullSyncRhythm)}
            live={
              isRunning && run.totalDocuments > 0
                ? {
                    percent: runProgressPercent,
                    processed: run.documentCount + run.documentsSkipped,
                    total: run.totalDocuments,
                  }
                : null
            }
            connectionTo={tabSearch('quelle')}
            quotaRemedy={quotaRemedy}
            scopeName={(key) =>
              details ? (connectorConfiguration?.scopeHero?.scopeName?.(key, details) ?? key) : key
            }
            // „Jetzt indizieren“ is the head's main action; the list adds only the full sync
            actions={
              mayManageDocuments && connectorConfiguration?.fullSyncRhythm ? (
                <Button
                  size="small"
                  disabled={isRunning}
                  onClick={() => void triggerIndexing(libraryId, connectorSourceType, 'FULL')}
                >
                  Vollabgleich starten
                </Button>
              ) : undefined
            }
          />
        </Box>
      )}

      {showSourceTab && (
        <Box
          role="tabpanel"
          id="library-tabpanel-quelle"
          aria-labelledby="library-tab-quelle"
          hidden={activeTab !== 'quelle'}
        >
          {/* Source paths and the run protocol stay behind the MANAGER
              bar (canEditSource); the Umfang above them is visible to every reader. */}
          {details ? (
            <LibrarySourceSection
              libraryId={libraryId}
              library={details}
              canEditSource={canEdit}
              erasing={erasing}
              connectionStatus={
                runsLoaded
                  ? connectionStatusOf(runs, connectorConfiguration?.setup?.signsIn ?? true)
                  : undefined
              }
              unreadable={unreadable}
            />
          ) : (
            <Typography sx={{ color: 'text.secondary' }}>
              {storeError
                ? 'Die Quelle lässt sich anzeigen, sobald die Bibliothek vollständig geladen ist.'
                : 'Quelle wird geladen …'}
            </Typography>
          )}
        </Box>
      )}

      {showMetadataTab && (
        <Box
          role="tabpanel"
          id="library-tabpanel-metadaten"
          aria-labelledby="library-tab-metadaten"
          hidden={activeTab !== 'metadaten'}
        >
          <LibraryMetadataTab libraryId={libraryId} mayChange={mayChange} canEdit={canEdit} />
        </Box>
      )}

      <Box
        role="tabpanel"
        id="library-tabpanel-freigaben"
        aria-labelledby="library-tab-freigaben"
        hidden={activeTab !== 'freigaben'}
      >
        <LibrarySharingTab
          libraryId={libraryId}
          library={library}
          details={details}
          privateLibrary={privateLibrary}
          canEdit={canEdit}
          externalAccessEnabled={externalAccessEnabled}
          shareCapVisible={shareCapVisible}
          onTransferred={() => reloadLibrary()}
          onSaveShareCap={(allowed) => handleSaveShareCap(allowed)}
          diagnosticsLockSaving={diagnosticsLockSaving}
          diagnosticsLockError={diagnosticsLockError}
          onToggleDiagnosticsLock={() => void handleToggleDiagnosticsLock()}
          onDismissDiagnosticsLockError={() => setDiagnosticsLockError(null)}
        />
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
