import Alert from '@mui/material/Alert'

interface LibraryRoleNoticesProps {
  canTrigger: boolean
  canEdit: boolean
  isAdministrativeOverride: boolean
}

/** What the own role allows in this library, stated once above the areas. */
export default function LibraryRoleNotices({
  canTrigger,
  canEdit,
  isAdministrativeOverride,
}: LibraryRoleNoticesProps) {
  return (
    <>
      {/* One hint about the own role, not two — the documents area no longer
         carries a second one. The two levels say different things: whoever may maintain documents
         does not merely read. */}
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
    </>
  )
}
