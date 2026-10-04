import { useState } from 'react'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import type { SourceTypeKey } from '../../types/api'
import { useSourceTypes } from '../../hooks/useSourceTypes'
import { confirmAction } from '../../stores/confirmStore'
import { useLibraryStore } from '../../stores/libraryStore'
import { notify } from '../../stores/notificationStore'
import LibraryConnectionDialog from './LibraryConnectionDialog'

interface LibraryConnectionPanelProps {
  libraryId: string
  sourceType: SourceTypeKey
  connectionProfile?: { id: string; name: string } | null
  /** Whether the dialog to connect or switch is open - also opened from the notices above. */
  dialogOpen: boolean
  onDialogOpenChange: (open: boolean) => void
}

/**
 * The profile of a library in the Bereich „Anbindung“, for its managers: which one it runs on and
 * the actions „Zugang zuordnen“, „Zugang wechseln“ and „Zugang lösen“ - the last one not where the
 * type is usable only through profiles. A type without profiles shows nothing here.
 */
export default function LibraryConnectionPanel({
  libraryId,
  sourceType,
  connectionProfile,
  dialogOpen,
  onDialogOpenChange,
}: LibraryConnectionPanelProps) {
  const { sourceTypes } = useSourceTypes()
  const releaseLibraryFromProfile = useLibraryStore((s) => s.releaseLibraryFromProfile)
  const [releasing, setReleasing] = useState(false)
  const descriptor = sourceTypes.find((d) => d.type === sourceType)
  const admitsProfiles = descriptor !== undefined && descriptor.profileSupport !== 'FORBIDDEN'
  const current = connectionProfile ?? null

  async function handleRelease() {
    if (!current) return
    const confirmed = await confirmAction({
      question: `Bibliothek vom Zugang „${current.name}“ lösen?`,
      consequence:
        'Die Bibliothek behält ihre Adresse und ihre Zugangsdaten und läuft mit ihnen als eigene Adresse weiter.',
      confirmLabel: 'Lösen',
      tone: 'caution',
    })
    if (!confirmed) return
    setReleasing(true)
    try {
      await releaseLibraryFromProfile(libraryId)
      notify(`Die Bibliothek ist vom Zugang „${current.name}“ gelöst.`, 'success')
    } catch (err) {
      notify(err instanceof Error ? err.message : 'Der Zugang ließ sich nicht lösen.', 'error')
    } finally {
      setReleasing(false)
    }
  }

  if (!current && !admitsProfiles) return null

  return (
    <Stack
      direction="row"
      spacing={1}
      useFlexGap
      sx={{ alignItems: 'center', flexWrap: 'wrap' }}
      data-testid="connection-profile-panel"
    >
      <Typography variant="body2" data-testid="connection-profile">
        {current ? `Zugang: ${current.name}` : 'Zugang: keiner, eigene Adresse'}
      </Typography>
      {admitsProfiles && (
        <Button size="small" variant="outlined" onClick={() => onDialogOpenChange(true)}>
          {current ? 'Zugang wechseln' : 'Zugang zuordnen'}
        </Button>
      )}
      {admitsProfiles && current && !descriptor.profileRequired && (
        <Button
          size="small"
          variant="text"
          disabled={releasing}
          onClick={() => void handleRelease()}
        >
          Zugang lösen
        </Button>
      )}
      {descriptor && admitsProfiles && (
        <LibraryConnectionDialog
          // a fresh instance on every opening starts without a previous choice or error
          key={dialogOpen ? 'connection-open' : 'connection-closed'}
          open={dialogOpen}
          onClose={() => onDialogOpenChange(false)}
          libraryId={libraryId}
          descriptor={descriptor}
          current={current}
        />
      )}
    </Stack>
  )
}
