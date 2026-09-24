import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import PageSection from '../PageSection'
import SectionHead from '../SectionHead'
import SubjectPicker from '../permissions/SubjectPicker'
import {
  confirmExternalSubject,
  emptySubjectSelection,
  groupLabel,
  selectedSubjectId,
  type SubjectSelection,
} from '../permissions/subjectSelection'
import { confirmAction } from '../../stores/confirmStore'
import { successionAwareMessage } from '../succession/successionConflict'
import { transferAssetOwnership } from '../../services/api'
import type { AssetOwnerType, AssetType } from '../../types/api'
import { assetTypeLabel } from '../../utils/labels'

export interface AssetOwnerSectionProps {
  assetType: AssetType
  assetId: string
  ownerType: AssetOwnerType
  /** Der Anzeigename, soweit er genannt werden darf; sonst steht nur die Art der Stelle da. */
  ownerName?: string | null
  /** Nur der Eigentümer selbst übergibt weiter — für alle anderen ist der Abschnitt eine Auskunft. */
  canTransfer: boolean
  /** Neu laden, nachdem das Eigentum gewechselt hat. */
  onTransferred: () => void | Promise<void>
}

/**
 * Wer für ein Asset geradesteht — und, für den Eigentümer selbst, die Übergabe an eine andere
 * Stelle (#1941). Eine Gruppe als Eigentümerin übersteht einen Personalwechsel; deshalb steht sie
 * hier gleichberechtigt neben einer Person.
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
    <PageSection
      title="Eigentümer"
      description={`Die Stelle, die für diese ${noun} geradesteht — sie vergibt Rechte, ändert die Auffindbarkeit und kann sie löschen.`}
      action={
        canTransfer && !open ? (
          <Button variant="outlined" size="small" onClick={() => setOpen(true)}>
            Eigentum übergeben
          </Button>
        ) : undefined
      }
    >
      <Stack spacing={2}>
        <Box>
          <Typography sx={{ fontSize: 14, fontWeight: 600 }}>
            {ownerName ??
              (ownerType === 'GROUP' ? 'Nicht benannte Gruppe' : 'Nicht benannte Person')}
          </Typography>
          <Typography variant="caption" sx={{ color: 'text.secondary' }}>
            {kindLabel}
            {ownerType === 'GROUP'
              ? ' — eine Gruppe als Eigentümerin übersteht einen Personalwechsel.'
              : ''}
          </Typography>
        </Box>

        {open && (
          <Stack spacing={2} sx={{ pt: 1, borderTop: 1, borderColor: 'divider' }}>
            <SectionHead component="h3">Eigentum übergeben</SectionHead>
            {error && <Alert severity="error">{error}</Alert>}
            <SubjectPicker
              labelId="asset-owner-transfer-subject-label"
              value={subject}
              onChange={setSubject}
            />
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
      </Stack>
    </PageSection>
  )
}
