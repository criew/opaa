import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Typography from '@mui/material/Typography'
import { plural } from '@/shared/utils/format'
import { getMetadataChangeImpact } from '../../api/libraryMetadataApi'
import type { MetadataChangeImpactResponse, MetadataChangeKind } from '@/shared/api/types'
import { formatDuration } from './metadataFieldText'

/**
 * The Folgekosten of a planned change, as concrete as the specification demands: documents,
 * chunks and the expected runtime of the Nachlauf - or the explicit statement that it costs
 * nothing. Loads on mount and on every change of the planned operation.
 */
export default function ChangeImpactNotice({
  libraryId,
  fieldKey,
  change,
  valueCode,
}: {
  libraryId: string
  fieldKey: string
  change: MetadataChangeKind
  valueCode?: string
}) {
  // Keyed by the planned operation instead of reset in the effect: an answer to an earlier
  // operation is then simply not the current one, and nothing has to be cleared synchronously.
  const requestKey = `${libraryId}|${fieldKey}|${change}|${valueCode ?? ''}`
  const [answer, setAnswer] = useState<{
    key: string
    impact: MetadataChangeImpactResponse | null
    failed: boolean
  }>({ key: '', impact: null, failed: false })

  useEffect(() => {
    let cancelled = false
    getMetadataChangeImpact(libraryId, fieldKey, change, valueCode)
      .then((result) => {
        if (!cancelled) setAnswer({ key: requestKey, impact: result, failed: false })
      })
      .catch(() => {
        if (!cancelled) setAnswer({ key: requestKey, impact: null, failed: true })
      })
    return () => {
      cancelled = true
    }
  }, [requestKey, libraryId, fieldKey, change, valueCode])

  const current = answer.key === requestKey ? answer : { impact: null, failed: false }
  const impact = current.impact
  const failed = current.failed

  if (failed) {
    return (
      <Alert severity="warning" sx={{ mt: 2 }}>
        Die Folgekosten konnten nicht ermittelt werden.
      </Alert>
    )
  }
  if (!impact) {
    return (
      <Typography variant="body2" sx={{ color: 'text.secondary', mt: 2 }}>
        Folgekosten werden ermittelt …
      </Typography>
    )
  }
  if (!impact.reembeddingRequired) {
    return (
      <Alert severity="success" sx={{ mt: 2 }}>
        {impact.affectedDocuments === 0
          ? 'Diese Änderung hat keine Folgekosten.'
          : `${plural(impact.affectedDocuments, 'Dokument ist', 'Dokumente sind')} betroffen; neu eingebettet werden muss nichts.`}
      </Alert>
    )
  }
  return (
    <Alert severity="warning" sx={{ mt: 2 }}>
      {`${plural(impact.affectedChunks, 'Abschnitt', 'Abschnitte')} in `}
      {`${plural(impact.affectedDocuments, 'Dokument', 'Dokumenten')} neu einzubetten, `}
      {formatDuration(impact.estimatedSeconds)}
      {impact.rateSource === 'CONFIGURED' ? ' (geschätzte Rate)' : ' (gemessene Rate)'}. Das
      Speichern setzt nichts in Bewegung — der Nachlauf wird auf der Seite „Suche &amp; Indexierung“
      ausdrücklich gestartet.
    </Alert>
  )
}
