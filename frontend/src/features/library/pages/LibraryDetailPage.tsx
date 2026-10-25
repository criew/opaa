import { useEffect, useRef, useState } from 'react'
import { useParams, useSearchParams } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import IconButton from '@mui/material/IconButton'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import PlayArrowIcon from '@mui/icons-material/PlayArrow'
import { confluenceSettingsOf } from '../utils/confluenceSource'
import { useAuthStore } from '@/shared/session/authStore'
import { useLibraryStore } from '../stores/libraryStore'
import { IDLE_RUN_STATE, useIndexingStore } from '../stores/indexingStore'
import { assetRoleLabel, formatFileSize } from '@/shared/utils/labels'
import { documentSourceTypeLabel } from '../components/sources/sourceLabels'
import { AssetDetailHeader } from '@/features/asset'
import { responsibleParty } from '@/features/asset'
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
import LibraryHeaderNote from '../components/LibraryHeaderNote'
import LibraryDetailTabBar from '../components/LibraryDetailTabBar'
import { useDocumentStore } from '../stores/documentStore'
import { sourceRegistration } from '../components/sources/registry'
import type { LibraryDocumentResponse } from '@/shared/api/types'
import { PlainPageSections } from '@/shared/ui/PageSection'
import LibrarySteckbrief, {
  STECKBRIEF_MEDIA,
  STECKBRIEF_WIDTH,
  type SteckbriefTarget,
} from '../components/steckbrief/LibrarySteckbrief'
import { formatDay, libraryStateOf, runPercent } from '../components/steckbrief/libraryState'
import { useNextSteps } from '../components/steckbrief/nextSteps'
import { sourceSummaryOf, unreadableScopeNames } from '../components/steckbrief/sourceSummary'

import LibraryMetadataTab from '../components/LibraryMetadataTab'
import LibrarySharingTab from '../components/LibrarySharingTab'

/** The source type's short chip form (guidelines 5.5), one line even at 320 px (D1). */
function sourceChipLabel(sourceType: LibraryDocumentResponse['sourceType']): string {
  const short: Partial<Record<LibraryDocumentResponse['sourceType'], string>> = {
    HTTP_DIRECTORY: 'Web',
    RSS_FEED: 'Feed',
    SMB: 'SMB',
    S3: 'S3',
  }
  return short[sourceType] ?? documentSourceTypeLabel(sourceType)
}

const NO_DOCUMENTS: LibraryDocumentResponse[] = []

function spreadLabel(spaceCount: number): string {
  if (spaceCount === 0) return 'in keinem Space'
  return spaceCount === 1 ? 'in 1 Space' : `in ${spaceCount} Spaces`
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
  const newestRunCategory = useIndexingStore((s) =>
    libraryId ? s.runsByLibrary[libraryId]?.failureCategory : undefined,
  )
  const quotaExhausted = privateLibrary && !erasing && newestRunCategory === 'QUOTA_EXHAUSTED'
  const ownsPrivateLibrary = privateLibrary && library?.myRole === 'OWNER'
  const [storageRefreshToken, setStorageRefreshToken] = useState(0)
  const privateStorage = useMyPrivateStorage(ownsPrivateLibrary, storageRefreshToken)

  // The connector-only concerns (status polling, trigger actions, the "Indizierung" area) hang
  // off the details' sourceType; a plain UPLOAD library has none of them.
  const connectorSourceType = details && details.sourceType !== 'UPLOAD' ? details.sourceType : null

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

  useEffect(() => {
    if (!libraryId || !connectorSourceType) return
    void loadStatus(libraryId, connectorSourceType)
    return () => stopPolling(libraryId)
  }, [libraryId, connectorSourceType, loadStatus, stopPolling])

  const isRunning = run.status === 'RUNNING'
  const statusLoaded = useIndexingStore((s) =>
    libraryId ? Boolean(s.runsByLibrary[libraryId]?.statusLoaded) : false,
  )
  // The newest run decides the state word, as the run list shows it (D3); managers load the list.
  const runHistory = useIndexingStore((s) =>
    libraryId ? s.runHistoryByLibrary[libraryId] : undefined,
  )
  const loadRunHistory = useIndexingStore((s) => s.loadRunHistory)
  useEffect(() => {
    if (!libraryId || !connectorSourceType || !canEdit) return
    void loadRunHistory(libraryId)
  }, [libraryId, connectorSourceType, canEdit, loadRunHistory, isRunning])
  const uploadErrors = useDocumentStore((s) => s.uploadErrors)
  const [uploadActionSlot, setUploadActionSlot] = useState<HTMLElement | null>(null)
  const loadedDocuments = useDocumentStore(
    (s) => (libraryId ? s.documentsByLibrary[libraryId] : undefined) ?? NO_DOCUMENTS,
  )

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
    return targetSearch({ tab })
  }

  /** A tab and, in the Reiter „Quelle“, the row it scrolls to (`row`). */
  function targetSearch(target: SteckbriefTarget): string {
    const next = new URLSearchParams(searchParams)
    if (target.tab === 'dokumente') next.delete('tab')
    else next.set('tab', target.tab)
    if (target.row) next.set('row', target.row)
    else next.delete('row')
    const query = next.toString()
    return query ? `?${query}` : ''
  }

  // A link of the Steckbrief names a row: bring it into view once the tab shows it.
  const rowParam = searchParams.get('row')
  useEffect(() => {
    if (!rowParam) return
    const frame = requestAnimationFrame(() => {
      const target =
        rowParam === 'runs'
          ? document.getElementById('library-runs-title')
          : document.querySelector<HTMLElement>(`[data-row="edit-source-row-${rowParam}"]`)
      target?.scrollIntoView({ block: 'start' })
    })
    return () => cancelAnimationFrame(frame)
  }, [rowParam, activeTab, details])

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

  const nextSteps = useNextSteps({
    libraryId: libraryId ?? '',
    sourceType: details?.sourceType ?? null,
    justCreated: searchParams.get('justCreated') === '1',
    mayChange,
    documentCount: library?.documentCount ?? 0,
    spaceCount: catalog.entry?.spaceCount,
    pushSecretSet: Boolean(details?.pushSecretSet),
    // read before the state word exists: the same facts it is made of
    sourceConnected:
      !details?.sourceBlock &&
      !(
        (runHistory?.[0]?.status ?? run.status) === 'FAILED' &&
        ['AUTH', 'CREDENTIALS_REJECTED'].includes(
          runHistory?.[0]?.failureCategory ?? run.failureCategory ?? '',
        )
      ),
    metadataEnabled: showMetadataTab,
  })

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
    ...(showSourceTab ? ([{ value: 'quelle', label: 'Quelle' }] as const) : []),
    ...(showMetadataTab ? ([{ value: 'metadaten', label: 'Metadaten' }] as const) : []),
    { value: 'freigaben', label: 'Freigaben' },
    { value: 'zuordnungen', label: 'Zuordnungen' },
  ]

  function associationsChanged() {
    catalog.reload()
    setAssociationsVersion((version) => version + 1)
  }

  const state = libraryStateOf({
    library: { ...library, ...(details ?? {}), sourceType: knownSourceType ?? 'UPLOAD' },
    erasing,
    status: run,
    newestRun: runHistory?.[0] ?? null,
    uploadErrors,
    uploadDocuments:
      knownSourceType === 'UPLOAD'
        ? {
            failed: loadedDocuments.filter((document) => document.status === 'FAILED').length,
            pending: loadedDocuments.filter((document) => document.status === 'PENDING').length,
            partial: loadedDocuments.length < (library.documentCount ?? 0),
          }
        : undefined,
    signsIn: sourceRegistration(knownSourceType)?.configuration?.setup?.signsIn !== false,
  })
  const documentCount = library.documentCount ?? 0
  const responsible = responsibleParty({
    ownerType: library.ownerType,
    ownerLabel: library.ownerName,
    succession: details?.succession ?? library.succession,
  })
  const summary = details && connectorSourceType ? sourceSummaryOf(details, libraryId) : null
  const isUpload = knownSourceType === 'UPLOAD'

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <AssetDetailHeader
        compact
        assetType="KNOWLEDGE_LIBRARY"
        assetId={libraryId}
        name={library.name}
        description={library.description}
        isPublic={library.reach.allAccounts}
        badges={
          <>
            {privateLibrary && <PrivateMark />}
            {erasing && <ErasingMark />}
            {details && (
              // a private library carries „Privat“ as well: below sm its source chip gives way (D1)
              <Box
                component="span"
                sx={{ display: privateLibrary ? { xs: 'none', sm: 'inline-flex' } : 'inline-flex' }}
              >
                <MetaBadge>{sourceChipLabel(details.sourceType)}</MetaBadge>
              </Box>
            )}
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
        extent=""
        responsible={responsible}
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
          knownSourceType === 'UPLOAD' && mayManageDocuments ? (
            // the documents tab puts its „Hochladen“ here, the place of the main action (D1)
            <Box ref={setUploadActionSlot} sx={{ display: 'flex' }} />
          ) : (
            connectorSourceType &&
            mayManageDocuments && (
              <>
                <Button
                  // one primary action per surface: the rows of the Reiter „Quelle“ save with theirs
                  variant="outlined"
                  size="small"
                  startIcon={<PlayArrowIcon />}
                  onClick={() => void triggerIndexing(libraryId, connectorSourceType)}
                  // the run's progress stands once, in the Steckbrief (D7)
                  disabled={isRunning}
                  sx={{ display: { xs: 'none', sm: 'inline-flex' } }}
                >
                  Jetzt indizieren
                </Button>
                <Tooltip title="Jetzt indizieren" describeChild>
                  <Box component="span" sx={{ display: { sm: 'none' } }}>
                    <IconButton
                      aria-label="Jetzt indizieren"
                      onClick={() => void triggerIndexing(libraryId, connectorSourceType)}
                      disabled={isRunning}
                    >
                      <PlayArrowIcon />
                    </IconButton>
                  </Box>
                </Tooltip>
              </>
            )
          )
        }
        // ADR-0036, Entscheidung 6: state and addressee for every reader - no date, no former
        // owner, no reason.
        note={
          <LibraryHeaderNote
            succession={details?.succession}
            erasureRequestedAt={erasureRequestedAt}
            erasureNoticeRef={erasureNoticeRef}
            quotaExhausted={quotaExhausted}
          />
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
          Die Angaben zu dieser Bibliothek konnten nicht vollständig geladen werden.
        </Alert>
      )}

      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: 'minmax(0, 1fr)',
          gridTemplateAreas: '"toggle" "main"',
          [STECKBRIEF_MEDIA]: {
            gridTemplateColumns: `minmax(0, 1fr) ${STECKBRIEF_WIDTH}px`,
            gridTemplateAreas: '"main side"',
            columnGap: 5,
          },
        }}
      >
        <LibrarySteckbrief
          state={state}
          // until the first status answer a connector library has no state yet (no flicker, P39)
          stateLoading={Boolean(connectorSourceType) && !statusLoaded}
          progress={isRunning ? runPercent(run) : null}
          statusUnknown={isRunning && run.statusUnknown}
          onRetryStatus={() => retryStatusPolling(libraryId)}
          source={
            summary
              ? {
                  // a reader sees the scope only, as in the Reiter „Quelle“
                  address: canEdit ? summary.address : null,
                  scope: summary.scope,
                  unreadable: details ? unreadableScopeNames(run.unlistedScopeKeys, details) : [],
                  schedule: canEdit ? summary.schedule : null,
                  nextRun: canEdit ? summary.nextRun : null,
                }
              : null
          }
          upload={
            isUpload
              ? {
                  storage:
                    details?.storageQuotaBytes != null && details.storageUsedBytes != null
                      ? `${formatFileSize(details.storageUsedBytes)} von ${formatFileSize(details.storageQuotaBytes)} belegt`
                      : null,
                  lastUpload: formatDay(library.updatedAt),
                  rejections: uploadErrors,
                }
              : null
          }
          documents={
            documentCount > 0
              ? `${documentCount.toLocaleString('de-DE')} ${documentCount === 1 ? 'Dokument' : 'Dokumente'}`
              : null
          }
          privateStorage={
            privateStorage
              ? privateStorage.quotaBytes > 0
                ? `${formatFileSize(privateStorage.usedBytes)} von ${formatFileSize(privateStorage.quotaBytes)} privat belegt`
                : `${formatFileSize(privateStorage.usedBytes)} privat belegt`
              : null
          }
          spaces={catalog.entry?.spaceCount ? spreadLabel(catalog.entry.spaceCount) : null}
          responsible={responsible.label}
          // the step of the open tab stands there as its own action already
          steps={nextSteps.steps.filter((step) => step.tab !== activeTab)}
          activeTab={activeTab}
          causeReachable={canEdit || state.cause === 'documents' || state.cause === 'sharing'}
          onDismissSteps={nextSteps.dismiss}
          targetSearch={targetSearch}
        />
        <Box sx={{ gridArea: 'main', minWidth: 0 }}>
          <LibraryDetailTabBar activeTab={activeTab} tabs={tabs} tabSearch={tabSearch} />
          <PlainPageSections>
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
                  actionSlot={uploadActionSlot}
                  quotaFull={
                    details.storageQuotaBytes != null &&
                    details.storageUsedBytes != null &&
                    details.storageUsedBytes >= details.storageQuotaBytes
                  }
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
          </PlainPageSections>
        </Box>
      </Box>
    </Box>
  )
}
