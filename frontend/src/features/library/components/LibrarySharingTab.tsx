import Stack from '@mui/material/Stack'
import type { LibraryListResponse, LibraryResponse } from '@/shared/api/types'
import { AssetAccessDerivationSection } from '@/features/asset'
import { AssetGrantsSection } from '@/features/asset'
import { AssetOwnerSection } from '@/features/asset'
import DiagnosticsLockControl from './DiagnosticsLockControl'
import LibraryExternalAccessSection from './LibraryExternalAccessSection'
import ShareCapSwitch from './ShareCapSwitch'

interface LibrarySharingTabProps {
  libraryId: string
  library: LibraryResponse | LibraryListResponse
  details: LibraryResponse | undefined
  privateLibrary: boolean
  canEdit: boolean
  externalAccessEnabled: boolean
  shareCapVisible: boolean
  onTransferred: () => Promise<void>
  /** A rejection is passed on to the switch that triggered it. */
  onSaveShareCap: (allAccountsGrantAllowed: boolean) => Promise<void>
  diagnosticsLockSaving: boolean
  diagnosticsLockError: string | null
  onToggleDiagnosticsLock: () => void
  onDismissDiagnosticsLockError: () => void
}

/** The „Freigaben" area: owner, permissions, external access, diagnostics lock, derivation. */
export default function LibrarySharingTab({
  libraryId,
  library,
  details,
  privateLibrary,
  canEdit,
  externalAccessEnabled,
  shareCapVisible,
  onTransferred,
  onSaveShareCap,
  diagnosticsLockSaving,
  diagnosticsLockError,
  onToggleDiagnosticsLock,
  onDismissDiagnosticsLockError,
}: LibrarySharingTabProps) {
  return (
    <>
      {/* The order of the target design: owner · permissions · external
           access · diagnostics lock · derivation. Every section saves on its own;
           there is no shared „Speichern" button across sections. */}
      <Stack>
        <AssetOwnerSection
          assetType="KNOWLEDGE_LIBRARY"
          assetId={libraryId}
          ownerType={library.ownerType}
          ownerName={library.ownerName}
          // Only whoever holds the ownership may hand over - myRole carries OWNER for a system
          // administration anyway, and the endpoint lets exactly that do so.
          canTransfer={library.myRole === 'OWNER' && !privateLibrary}
          onTransferred={onTransferred}
        />

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
                    onSave={onSaveShareCap}
                  />
                ) : null
              }
            />

            {externalAccessEnabled && <LibraryExternalAccessSection libraryId={libraryId} />}
          </>
        )}

        {details && (
          <DiagnosticsLockControl
            locked={details.diagnosticsLocked ?? true}
            // myRole bypasses to OWNER for a system admin unconditionally
            // (LibraryResponse#myRole) - this dedicated field mirrors the backend's stricter
            // holdsIndependentOwnerRole instead, so an admin without an independent OWNER
            // grant never sees a button that is guaranteed to fail with 403.
            canToggle={details.diagnosticsLockToggleable ?? false}
            saving={diagnosticsLockSaving}
            error={diagnosticsLockError}
            onToggle={onToggleDiagnosticsLock}
            onDismissError={onDismissDiagnosticsLockError}
          />
        )}

        {!privateLibrary && (
          <AssetAccessDerivationSection assetType="KNOWLEDGE_LIBRARY" assetId={libraryId} />
        )}
      </Stack>
    </>
  )
}
