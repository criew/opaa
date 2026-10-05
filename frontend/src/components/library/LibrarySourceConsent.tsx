import { useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import type { LibrarySourceConnection } from '../../types/api'
import { disconnectLibrarySource } from '../../services/libraryApi'
import { confirmAction } from '../../stores/confirmStore'
import { useLibraryStore } from '../../stores/libraryStore'
import { notify } from '../../stores/notificationStore'
import KeyValueList from '../KeyValueList'
import {
  formatConsentTime,
  responsibleLabel,
  sourceConnectionEndLabel,
} from './sourceConnectionLabels'

interface LibrarySourceConsentProps {
  libraryId: string
  consent: LibrarySourceConnection
  /** Absent once the library's profile is gone: it is reconnected after „Zugang zuordnen“. */
  onReconnect?: () => void
}

/**
 * The library's own source connection ("Quelle verbinden") as its managers see it: the account at
 * the provider, who answers for it, its end, and the actions „Neu verbinden“ and „Trennen“.
 */
export default function LibrarySourceConsent({
  libraryId,
  consent,
  onReconnect,
}: LibrarySourceConsentProps) {
  const loadLibraryDetails = useLibraryStore((s) => s.loadLibraryDetails)
  const [disconnecting, setDisconnecting] = useState(false)
  const ended = consent.endedCause != null

  async function handleDisconnect() {
    const confirmed = await confirmAction({
      question: 'Verbindung der Quelle trennen?',
      consequence:
        'Die Zustimmung beim Anbieter wird gelöscht und, wo der Anbieter das anbietet, widerrufen. Der Inhalt bleibt durchsuchbar, wird aber erst wieder aktualisiert, wenn die Quelle neu verbunden ist.',
      confirmLabel: 'Trennen',
      tone: 'danger',
    })
    if (!confirmed) return
    setDisconnecting(true)
    try {
      await disconnectLibrarySource(libraryId)
      notify('Die Verbindung der Quelle ist getrennt.', 'success')
      await loadLibraryDetails(libraryId)
    } catch (err) {
      notify(
        err instanceof Error ? err.message : 'Die Verbindung der Quelle ließ sich nicht trennen.',
        'error',
      )
    } finally {
      setDisconnecting(false)
    }
  }

  return (
    <Box data-testid="source-connection" sx={{ mt: 1 }}>
      <Typography variant="body2" sx={{ fontWeight: 600, mb: 0.5 }}>
        Quellverbindung
      </Typography>
      <KeyValueList
        entries={[
          { label: 'Verbunden als', value: consent.accountLabel ?? 'Konto nicht genannt' },
          { label: 'Verbunden am', value: formatConsentTime(consent.connectedAt) },
          {
            label: 'Verantwortlich',
            value: consent.responsible ? responsibleLabel(consent.responsible) : '—',
          },
          {
            label: 'Zustimmung endet',
            value: consent.expiresAt ? formatConsentTime(consent.expiresAt) : '',
            hidden: ended || !consent.expiresAt,
          },
          {
            label: 'Beendet',
            value: consent.endedCause
              ? `${sourceConnectionEndLabel(consent.endedCause)}${consent.endedAt ? `, ${formatConsentTime(consent.endedAt)}` : ''}`
              : '',
            hidden: !ended,
          },
        ]}
      />
      {!onReconnect && (
        <Typography variant="body2" sx={{ color: 'text.secondary', mt: 1 }}>
          Der Zugang dieser Bibliothek wurde entfernt. Ordnen Sie sie über „Zugang zuordnen“ einem
          anderen Zugang zu; danach lässt sich die Quelle dort neu verbinden.
        </Typography>
      )}
      <Stack direction="row" spacing={1} sx={{ mt: 1 }}>
        {onReconnect && (
          <Button size="small" variant="outlined" onClick={onReconnect}>
            Neu verbinden
          </Button>
        )}
        {!ended && (
          <Button
            size="small"
            variant="text"
            disabled={disconnecting}
            onClick={() => void handleDisconnect()}
          >
            Trennen
          </Button>
        )}
      </Stack>
    </Box>
  )
}
