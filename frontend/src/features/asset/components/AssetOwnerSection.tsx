import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { TextAction } from '@/shared/ui/DetailSection'
import ValueRow from '@/shared/ui/ValueRow'
import { SubjectPicker } from '@/features/principal'
import {
  confirmExternalSubject,
  emptySubjectSelection,
  groupLabel,
  selectedSubjectId,
  type SubjectSelection,
} from '@/features/principal'
import { confirmAction } from '@/shared/stores/confirmStore'
import { successionAwareMessage } from '@/features/succession'
import { transferAssetOwnership } from '../api/assetApi'
import type { AssetOwnerType, AssetType } from '@/shared/api/types'
import { assetTypeLabel } from '../../../shared/assetTypes'

export interface AssetOwnerSectionProps {
  assetType: AssetType
  assetId: string
  ownerType: AssetOwnerType
  /** The display name, as far as it may be named; otherwise only the kind of party is shown. */
  ownerName?: string | null
  /** Only the owner hands over — for everyone else the section is information. */
  canTransfer: boolean
  /** Reload after the ownership has changed. */
  onTransferred: () => void | Promise<void>
}

/**
 * Who answers for an asset — and, for the owner, the handover to another
 * party. A group as owner survives a change of staff; that is why it stands
 * here on an equal footing with a person.
 */
export default function AssetOwnerSection({
  assetType,
  assetId,
  ownerType,
  ownerName,
  canTransfer,
  onTransferred,
}: AssetOwnerSectionProps) {
  const [transferring, setTransferring] = useState(false)
  const [open, setOpen] = useState(false)
  const [subject, setSubject] = useState<SubjectSelection>(emptySubjectSelection)
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  const noun = assetTypeLabel(assetType)
  const kindLabel = ownerType === 'GROUP' ? 'Gruppe' : 'Person'

  function reset() {
    setOpen(false)
    setSubject(emptySubjectSelection)
    setError(null)
  }

  async function handleTransfer() {
    setError(null)
    const targetId = selectedSubjectId(subject)
    if (!targetId) {
      setError(
        subject.type === 'GROUP' ? 'Bitte eine Gruppe auswählen' : 'Bitte eine Person auswählen',
      )
      return
    }
    const targetLabel =
      subject.type === 'GROUP'
        ? subject.group
          ? groupLabel(subject.group)
          : targetId
        : (subject.user?.displayName ?? subject.user?.email ?? targetId)
    if (!(await confirmExternalSubject(subject))) return
    const confirmed = await confirmAction({
      question: `Eigentum an „${targetLabel}" übergeben?`,
      consequence:
        `Die Zuständigkeit für diese ${noun} geht damit über. Sie behalten Ihre Rolle nur, wenn ` +
        'sie Ihnen gesondert eingeräumt wird — die Eigentümerrolle geht mit.',
      confirmLabel: 'Übergeben',
      tone: 'danger',
    })
    if (!confirmed) return
    setSaving(true)
    try {
      await transferAssetOwnership(assetType, assetId, {
        ownerType: subject.type === 'GROUP' ? 'GROUP' : 'USER',
        ownerId: targetId,
      })
      reset()
      setTransferring(true)
      await onTransferred()
    } catch (err) {
      setError(successionAwareMessage(err, 'Eigentum konnte nicht übergeben werden'))
    } finally {
      setSaving(false)
      setTransferring(false)
    }
  }

  return (
    <ValueRow
      title="Eigentümer"
      value={
        <>
          <Box component="span" sx={{ fontWeight: 600 }}>
            {ownerName ??
              (ownerType === 'GROUP' ? 'Nicht benannte Gruppe' : 'Nicht benannte Person')}
          </Box>
          <Box component="span" sx={{ color: 'text.secondary' }}>
            {` · ${kindLabel}`}
          </Box>
        </>
      }
      action={
        canTransfer && !open ? (
          <TextAction onClick={() => setOpen(true)} context="Eigentum">
            Übergeben
          </TextAction>
        ) : undefined
      }
    >
      {open && (
        <Stack spacing={2}>
          <Typography component="h3" sx={{ fontSize: 14, fontWeight: 600 }}>
            Eigentum übergeben
          </Typography>
          {error && <Alert severity="error">{error}</Alert>}
          <SubjectPicker value={subject} onChange={setSubject} />
          <Typography variant="caption" sx={{ color: 'text.secondary' }}>
            Eine Gruppe erhält die Rolle „Verwaltung" — die Eigentümerrolle selbst bleibt an eine
            Person gebunden, damit sie nicht mit jedem neuen Mitglied mitwächst.
          </Typography>
          <Stack direction="row" spacing={1}>
            <Button
              variant="contained"
              size="small"
              onClick={() => void handleTransfer()}
              disabled={saving || transferring}
            >
              {saving ? 'Wird übergeben …' : 'Übergeben'}
            </Button>
            <Button size="small" onClick={reset} disabled={saving}>
              Abbrechen
            </Button>
          </Stack>
        </Stack>
      )}
    </ValueRow>
  )
}
