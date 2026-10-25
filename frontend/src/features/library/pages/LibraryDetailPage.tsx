import { useEffect, useRef, useState } from 'react'
import { useParams, useSearchParams } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Menu from '@mui/material/Menu'
import MenuItem from '@mui/material/MenuItem'
import Typography from '@mui/material/Typography'
import type { IndexingRunResponse } from '@/shared/api/types'
import { confluenceSettingsOf } from '../utils/confluenceSource'
import { sourceRegistration } from '../components/sources/registry'
import { useAuthStore } from '@/shared/session/authStore'
import { useLibraryStore } from '../stores/libraryStore'
import { IDLE_RUN_STATE, useIndexingStore } from '../stores/indexingStore'
import { assetRoleLabel, formatFileSize } from '@/shared/utils/labels'
import { documentSourceTypeShortLabel } from '../components/sources/sourceLabels'
import { AssetDetailHeader, HeaderFigure } from '@/features/asset'
import { responsibleParty } from '@/features/asset'
// a stable empty history: a selector must not return a fresh array for an unchanged slice
const NO_RUNS: IndexingRunResponse[] = []
import { ErasingMark, PrivateMark } from '@/features/asset'
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
import LibraryStateLink from '../components/LibraryStateLink'
import { libraryState } from '../components/libraryState'
import { CONTENT_WIDTH } from '../components/setup/rowStyles'
import { useDocumentUpload } from '../hooks/useDocumentUpload'
import { useDocumentStore } from '../stores/documentStore'
import { getLibraryDocuments } from '../api/libraryApi'
import LibraryUploadInputs from '../components/LibraryUploadInputs'
import LibraryDetailTabBar from '../components/LibraryDetailTabBar'
import LibraryMetadataTab from '../components/LibraryMetadataTab'
import LibrarySharingTab from '../components/LibrarySharingTab'

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
  const activeTab: LibraryDetailTab = resolveTab(
    searchParams.get('tab'),
    showSourceTab,
    showMetadataTab,
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
  const loadRunHistory = useIndexingStore((s) => s.loadRunHistory)
  const runs = useIndexingStore((s) =>
    libraryId ? (s.runHistoryByLibrary[libraryId] ?? NO_RUNS) : NO_RUNS,
  )

  useEffect(() => {
    if (!libraryId || !connectorSourceType) return
    void loadStatus(libraryId, connectorSourceType)
    // the head's state reads the newest run, so the history loads with the page
    void loadRunHistory(libraryId)
    return () => stopPolling(libraryId)
  }, [libraryId, connectorSourceType, loadStatus, loadRunHistory, stopPolling])

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
      void loadRunHistory(libraryId)
      setDocumentsRefreshToken((token) => token + 1)
      setStorageRefreshToken((token) => token + 1)
    }
    wasRunningRef.current = isRunning
  }, [isRunning, libraryId, loadLibraryDetails, loadRunHistory])

  // The running run's progress from the polled status - the head and the run row show this count.
  const running =
    connectorSourceType && isRunning && !run.statusUnknown
      ? {
          percent: runProgressPercent,
          done: run.documentCount + run.documentsSkipped,
          total: run.totalDocuments,
        }
      : null

  // Upload: documents whose processing failed - the head names their number.
  const isUploadLibrary = details?.sourceType === 'UPLOAD'
  const [failedDocuments, setFailedDocuments] = useState(0)
  const [pendingDocuments, setPendingDocuments] = useState(0)
  // the list polls its pending documents; when their states change, the head counts anew
  const shownPending = useDocumentStore((s) =>
    libraryId
      ? (s.documentsByLibrary[libraryId] ?? []).filter((doc) => doc.status === 'PENDING').length
      : 0,
  )
  useEffect(() => {
    if (!libraryId || !isUploadLibrary) return
    let cancelled = false
    const countOf = (status: 'FAILED' | 'PENDING', set: (n: number) => void) =>
      getLibraryDocuments(libraryId, { size: 1, status })
        .then((page) => {
          if (!cancelled) set(page.totalElements)
        })
        .catch(() => {})
    void countOf('FAILED', setFailedDocuments)
    void countOf('PENDING', setPendingDocuments)
    return () => {
      cancelled = true
    }
  }, [libraryId, isUploadLibrary, documentsRefreshToken, details?.documentCount, shownPending])
  const uploadQuotaRejected = useDocumentStore((s) =>
    s.uploadErrors.some((message) => /kontingent/i.test(message)),
  )
  const upload = useDocumentUpload(libraryId ?? '', mayManageDocuments, () => {
    if (libraryId) void loadLibraryDetails(libraryId)
  })
  const [uploadMenu, setUploadMenu] = useState<HTMLElement | null>(null)

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

  const state = libraryState({
    library: { ...library, ...details },
    documentCount: library.documentCount ?? 0,
    erasureRequestedAt,
    succession: details?.succession ?? library.succession,
    newestRun: runs[0] ?? null,
    runningPercent: running ? running.percent : null,
    failedDocuments,
    pendingDocuments,
    uploadQuotaRejected,
    signsIn: connectorConfiguration?.setup?.signsIn ?? true,
    canSeeSource: canEdit,
  })
  if (connectorSourceType && isRunning && run.statusUnknown) {
    state.detail = 'Stand unbekannt'
  }

  const tabs: Array<{ value: LibraryDetailTab; label: string }> = [
    { value: 'dokumente', label: 'Dokumente' },
    ...(showSourceTab ? ([{ value: 'quelle', label: 'Quelle' }] as const) : []),
    ...(showMetadataTab ? ([{ value: 'metadaten', label: 'Metadaten' }] as const) : []),
    { value: 'freigaben', label: 'Freigaben' },
    { value: 'zuordnungen', label: 'Zuordnungen' },
  ]

  function associationsChanged() {
    catalog.reload()
    setAssociationsVersion((version) => version + 1)
  }

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2, md: 5 }, overflowY: 'auto' }}>
      {/* one column for head, tabs and every tab's content - the width of wizard and editing */}
      <Box sx={{ maxWidth: CONTENT_WIDTH }}>
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
              {/* the short form of the catalog tile (guidelines 5.5), so the chips keep one line */}
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
          // storageQuotaBytes/storageUsedBytes reach a caller with at least MANAGER only; a full
          // quota is the state, not a second figure
          figures={
            <>
              {details?.storageQuotaBytes != null && details.storageUsedBytes != null && (
                <HeaderFigure>
                  {formatFileSize(details.storageUsedBytes)} von{' '}
                  {formatFileSize(details.storageQuotaBytes)}
                </HeaderFigure>
              )}
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
          spaceHref={tabSearch('zuordnungen')}
          responsible={responsibleParty({
            ownerType: library.ownerType,
            ownerLabel: library.ownerName,
            succession: details?.succession ?? library.succession,
          })}
          responsibleHref={tabSearch('freigaben')}
          status={<LibraryStateLink state={state} focusRef={erasureNoticeRef} />}
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
            connectorSourceType && mayManageDocuments ? (
              // one primary action per surface: the rows of the Reiter „Quelle" save with theirs;
              // while a run runs, its progress is the state, not a second text on the button
              <Button
                variant="outlined"
                size="small"
                onClick={() => void triggerIndexing(libraryId, connectorSourceType)}
                disabled={isRunning}
              >
                Jetzt indizieren
              </Button>
            ) : isUploadLibrary && mayManageDocuments ? (
              <>
                <Button
                  variant="outlined"
                  size="small"
                  aria-haspopup="menu"
                  onClick={(event) => setUploadMenu(event.currentTarget)}
                  disabled={upload.isUploading}
                >
                  {upload.isUploading ? 'Wird hochgeladen …' : 'Hochladen'}
                </Button>
                <Menu
                  anchorEl={uploadMenu}
                  open={uploadMenu !== null}
                  onClose={() => setUploadMenu(null)}
                  anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
                  transformOrigin={{ vertical: 'top', horizontal: 'right' }}
                >
                  <MenuItem
                    onClick={() => {
                      setUploadMenu(null)
                      upload.fileInputRef.current?.click()
                    }}
                  >
                    Dateien auswählen …
                  </MenuItem>
                  <MenuItem
                    onClick={() => {
                      setUploadMenu(null)
                      upload.folderInputRef.current?.click()
                    }}
                  >
                    Ordner auswählen …
                  </MenuItem>
                </Menu>
              </>
            ) : undefined
          }
        />
        {isUploadLibrary && mayManageDocuments && <LibraryUploadInputs upload={upload} />}

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
              <Button
                color="inherit"
                size="small"
                onClick={() => void loadLibraryDetails(libraryId)}
              >
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
              upload={upload}
              refreshToken={documentsRefreshToken}
              confluenceSpaces={confluenceSettingsOf(details).spaces}
              // The document count in the header comes from the library
              // itself, not from documentStore - without this it stays on whatever value was loaded
              // on mount even after an upload or delete changes it.
              onDocumentsChanged={() => void loadLibraryDetails(libraryId)}
            />
          )}
        </Box>

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
                runs={runs}
                running={running}
                unlistedScopeKeys={run.unlistedScopeKeys}
                statusUnknown={isRunning && run.statusUnknown}
                onRetryStatus={() => retryStatusPolling(libraryId)}
                onFullSync={
                  connectorSourceType &&
                  mayManageDocuments &&
                  connectorConfiguration?.fullSyncRhythm
                    ? () => void triggerIndexing(libraryId, connectorSourceType, 'FULL')
                    : undefined
                }
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
    </Box>
  )
}
