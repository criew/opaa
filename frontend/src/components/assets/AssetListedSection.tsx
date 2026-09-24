import { useState, type ReactNode } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import FormControlLabel from '@mui/material/FormControlLabel'
import FormHelperText from '@mui/material/FormHelperText'
import Stack from '@mui/material/Stack'
import PageSection from '../PageSection'
import { successionAwareMessage } from '../succession/successionConflict'
import type { AssetType } from '../../types/api'
import { assetTypeLabel } from '../../utils/labels'

export interface AssetListedSectionProps {
  assetType: AssetType
  /** Der gespeicherte Zustand; der Abschnitt führt seinen eigenen Entwurf bis „Speichern". */
  listed: boolean
  /** Speichert die Auffindbarkeit; eine Ablehnung bleibt im Abschnitt stehen. */
  onSave: (listed: boolean) => Promise<void>
  /** Eine anderswo gesetzte Obergrenze; der Schalter ist dann gesperrt und der Grund benannt. */
  listedCap?: boolean | null
  /** Der Schalter der Systemverwaltung zu genau dieser Obergrenze, wo es einen gibt. */
  capControl?: ReactNode
}

/**
 * „Im Katalog auffindbar" als eigener Abschnitt (#1941) — mit dem Satz, auf den es ankommt:
 * auffindbar heißt nicht lesbar. Gesperrt ist der Schalter nur dort, wo die Systemverwaltung die
 * Auffindbarkeit dieser Bibliothek überhaupt verboten hat.
 */
export default function AssetListedSection({
  assetType,
  listed,
  onSave,
  listedCap,
  capControl,
}: AssetListedSectionProps) {
  const [draft, setDraft] = useState<boolean | null>(null)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const noun = assetTypeLabel(assetType)
  const draftListed = draft ?? listed
  const changed = draftListed !== listed

  async function handleSave() {
    setError(null)
    setSaving(true)
    try {
      await onSave(draftListed)
      setDraft(null)
    } catch (err) {
      // Eine offene Nachfolge friert genau die Vergrößerung der Reichweite ein (ADR-0036/6) - die
      // Ablehnung benennt dann auch den Weg heraus.
      setError(successionAwareMessage(err, 'Auffindbarkeit konnte nicht gespeichert werden'))
    } finally {
      setSaving(false)
    }
  }

  return (
    <PageSection
      title="Im Katalog auffindbar"
      description={`Ob diese ${noun} im Katalog erscheint, auch für Personen ohne Berechtigung.`}
    >
      <Stack spacing={2}>
        {error && (
          <Alert severity="error" onClose={() => setError(null)}>
            {error}
          </Alert>
        )}
        <Box>
          <FormControlLabel
            control={
              <Checkbox
                checked={draftListed}
                disabled={listedCap === false}
                onChange={(e) => setDraft(e.target.checked)}
              />
            }
            label="Im Katalog auffindbar, auch ohne Berechtigung"
          />
          <FormHelperText>
            Sichtbar wird der Eintrag — Name, Beschreibung und zuständige Stelle —, nicht der
            Inhalt.
          </FormHelperText>
          {listedCap === false && (
            <FormHelperText>
              Die Systemverwaltung hat die Auffindbarkeit dieser {noun} im Katalog gesperrt.
            </FormHelperText>
          )}
        </Box>
        <Stack direction="row" spacing={1}>
          <Button
            variant="contained"
            size="small"
            onClick={() => void handleSave()}
            disabled={saving || !changed}
          >
            {saving ? 'Wird gespeichert …' : 'Auffindbarkeit speichern'}
          </Button>
        </Stack>
        {capControl}
      </Stack>
    </PageSection>
  )
}
