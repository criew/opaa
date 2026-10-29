import Alert from '@mui/material/Alert'
import { formatFileSize } from '@/shared/utils/labels'

interface LibraryQuotaNoticeProps {
  /** What the library occupies; null for a caller below MANAGER, who does not see it. */
  usedBytes: number | null | undefined
  /** The quota in force for the library, 0 for none; null like usedBytes. */
  quotaBytes: number | null | undefined
  privateLibrary: boolean
}

/**
 * A library above its quota, typically after the quota was lowered: nothing was deleted, only
 * intake that grows the library is refused until it is below again.
 */
export default function LibraryQuotaNotice({
  usedBytes,
  quotaBytes,
  privateLibrary,
}: LibraryQuotaNoticeProps) {
  if (usedBytes == null || quotaBytes == null || quotaBytes <= 0 || usedBytes <= quotaBytes) {
    return null
  }
  return (
    <Alert severity="warning" role="status" sx={{ mb: 2 }} data-testid="library-quota-over">
      Diese Bibliothek belegt mehr als ihr Speicherkontingent ({formatFileSize(usedBytes)} von{' '}
      {formatFileSize(quotaBytes)}). Alle Dokumente bleiben erhalten und durchsuchbar; Neues und
      größer gewordene Dateien nimmt sie erst wieder auf, wenn sie unter dem Kontingent liegt.{' '}
      {privateLibrary
        ? 'Das Kontingent je Bibliothek legt der Betrieb fest.'
        : 'Das Kontingent legt die Systemverwaltung fest.'}
    </Alert>
  )
}
