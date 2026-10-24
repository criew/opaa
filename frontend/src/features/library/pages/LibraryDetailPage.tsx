import { useEffect, useRef, useState } from 'react'
import { useParams, useSearchParams } from 'react-router'
import Alert from '@mui/material/Alert'
import AlertTitle from '@mui/material/AlertTitle'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import LinearProgress from '@mui/material/LinearProgress'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import DataUsageIcon from '@mui/icons-material/DataUsage'
import HistoryIcon from '@mui/icons-material/History'
import PlayArrowIcon from '@mui/icons-material/PlayArrow'
import { confluenceSettingsOf } from '../utils/confluenceSource'
import { sourceRegistration } from '../components/sources/registry'
import { useAuthStore } from '@/shared/session/authStore'
import { useLibraryStore } from '../stores/libraryStore'
import { IDLE_RUN_STATE, useIndexingStore } from '../stores/indexingStore'
import { assetRoleLabel, formatFileSize } from '@/shared/utils/labels'
import { documentSourceTypeLabel } from '../components/sources/sourceLabels'
import { AssetDetailHeader, HeaderFigure } from '@/features/asset'
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
  formatIndexedAt,
  resolveTab,
} from '../components/libraryDetailAccess'
import type { LibraryDetailTab } from '../components/libraryDetailAccess'
import { useLibraryDeletion } from '../components/useLibraryDeletion'
import LibraryHeaderNote from '../components/LibraryHeaderNote'
import LibraryScopeHero from '../components/LibraryScopeHero'
import LibraryRoleNotices from '../components/LibraryRoleNotices'
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
  // Takes the focus from „Erneut versuchen", which disappears with the notice it belongs to.
  const runStatusRef = useRef<HTMLDivElement>(null)

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

  const isRssFeedRun = Boolean(sourceRegistration(connectorSourceType)?.runCountsEntries)
  const runFailedSuffix =
    run.documentsFailed > 0 ? `, davon ${run.documentsFailed} fehlgeschlagen` : ''

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
        // ADR-0023, Entscheidung 4: the full reconciliation can be forced, from the „…“ menu
        menuItems={
          connectorSourceType && mayManageDocuments && connectorConfiguration?.fullSyncRhythm
            ? [
                {
                  label: 'Vollabgleich starten',
                  disabled: isRunning,
                  onClick: () => void triggerIndexing(libraryId, connectorSourceType, 'FULL'),
                },
              ]
            : undefined
        }
        actions={
          connectorSourceType &&
          mayManageDocuments && (
            <>
              <Button
                // one primary action per surface: the rows of the Reiter „Quelle“ save with theirs
                variant="outlined"
                size="small"
                startIcon={<PlayArrowIcon />}
                onClick={() => void triggerIndexing(libraryId, connectorSourceType)}
                disabled={isRunning}
              >
                {isRunning ? 'Indizierung läuft …' : 'Jetzt indizieren'}
              </Button>
            </>
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
      >
        {/* The header shows the scope only as a short line with a jump into the „Quelle"
           tab; it is complete there, for every role. The warning about a
           partially read scope, by contrast, stays here: it concerns the content that
           every view of the page shows, not the setting behind it. */}
        {details && connectorConfiguration?.scopeHero && (
          <LibraryScopeHero
            details={details}
            scopeHero={connectorConfiguration.scopeHero}
            unlistedScopeKeys={run.unlistedScopeKeys}
            sourceSearch={tabSearch('quelle')}
          />
        )}

        {/* A running indexing stays visible no matter which area below is active (guidelines
            5.7) - anchored in the hero with a pulsing dot as the one moving element. */}
        {connectorSourceType && isRunning && (
          <Box
            ref={runStatusRef}
            tabIndex={-1}
            sx={{ mt: 2.5, pt: 2, borderTop: 1, borderTopColor: 'divider', position: 'relative' }}
          >
            {run.statusUnknown ? (
              // The last known status stays RUNNING: failed fetches say nothing about the run.
              <Alert
                severity="warning"
                role="status"
                data-testid="library-run-status-unknown"
                action={
                  <Button
                    color="inherit"
                    size="small"
                    onClick={() => {
                      runStatusRef.current?.focus()
                      retryStatusPolling(libraryId)
                    }}
                  >
                    Erneut versuchen
                  </Button>
                }
              >
                <AlertTitle>Status unbekannt</AlertTitle>
                Der Stand des Indizierungslaufs konnte wiederholt nicht abgerufen werden. Der Lauf
                kann im Hintergrund weiterlaufen.
              </Alert>
            ) : (
              <>
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
              </>
            )}
          </Box>
        )}
      </AssetDetailHeader>

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
      <LibraryRoleNotices
        canTrigger={canTrigger}
        canEdit={canEdit}
        isAdministrativeOverride={isAdministrativeOverride}
      />

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
    </Box>
  )
}
