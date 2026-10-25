import { useState } from 'react'
import Box from '@mui/material/Box'
import type { LibraryResponse } from '@/shared/api/types'
import { TextAction } from '@/shared/ui/DetailSection'
import ConfluenceWebhookSection from './ConfluenceWebhookSection'
import S3EventSection from './S3EventSection'
import SummaryRow from './setup/SummaryRow'
import type { S3ScopeRef } from '../utils/s3Source'

/**
 * The fourth row of the Reiter „Quelle“ for Confluence and S3: whether the source reports its
 * changes right away (Confluence webhook, S3 event notification). The secret behind it needs the
 * library's id, so it is set up here, after the creation.
 */
export default function LibraryLiveUpdateRow({
  libraryId,
  library,
  canEdit,
}: {
  libraryId: string
  library: Pick<LibraryResponse, 'sourceType' | 'pushSecretSet' | 'sourceSettings'>
  canEdit: boolean
}) {
  const [open, setOpen] = useState(false)
  const set = Boolean(library.pushSecretSet)
  const confluence = library.sourceType === 'CONFLUENCE'
  return (
    <SummaryRow
      id="edit-source-row-live"
      title="Live-Abgleich"
      summary={set ? (confluence ? 'an, über den Webhook' : 'an, über Ereignisse') : 'aus'}
      open={open}
      onOpen={canEdit ? () => setOpen(true) : undefined}
      actionLabel={set ? 'Ändern' : 'Einrichten'}
      headingLevel="h2"
    >
      {confluence ? (
        <ConfluenceWebhookSection libraryId={libraryId} secretSet={library.pushSecretSet} />
      ) : (
        <S3EventSection
          libraryId={libraryId}
          tokenSet={library.pushSecretSet}
          scopes={(library.sourceSettings as { scopes?: S3ScopeRef[] } | null)?.scopes ?? []}
        />
      )}
      <Box sx={{ mt: 2, textAlign: 'right' }}>
        <TextAction onClick={() => setOpen(false)}>Schließen</TextAction>
      </Box>
    </SummaryRow>
  )
}
