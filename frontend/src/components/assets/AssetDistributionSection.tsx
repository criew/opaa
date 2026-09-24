import { useState, type ReactNode } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import FormControlLabel from '@mui/material/FormControlLabel'
import FormHelperText from '@mui/material/FormHelperText'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import type { AssetReach, AssetType } from '../../types/api'
import PageSection from '../PageSection'
import FieldLabel from '../wizard/FieldLabel'
import AssetGrantsDialog from '../permissions/AssetGrantsDialog'
import AccessDerivation from '../permissions/AccessDerivation'
import { successionAwareMessage } from '../succession/successionConflict'
import AssetSpacesList from './AssetSpacesList'
import { assetReachLabel, assetTypeLabel } from '../../utils/labels'

export interface AssetDistributionSectionProps {
  assetType: AssetType
  assetId: string
  assetName: string
  /**
   * How far the asset reaches right now - derived from its grants, never stored (#1931). Shown,
   * not edited: who reaches it is changed in the rights dialog.
   */
  reach: AssetReach
  /** The saved findability; the section keeps its own draft until "Freigabe speichern". */
  listed: boolean
  /** Saves the findability; a rejection is shown in the section, succession-aware. */
  onSave: (listed: boolean) => Promise<void>
  /** A ceiling on the findability set elsewhere; the switch is locked and the reason explained. */
  listedCap?: boolean | null
  /** Type-specific control below the fields, e.g. the connector library's share cap. */
  capControl?: ReactNode
  /** Type-specific part of the rights dialog, e.g. a library's release for Fremdzugänge. */
  grantsTypeSection?: ReactNode
}

/**
 * The one release section of every asset type: the derived reach, findability, the rights dialog,
 * the spaces the asset is associated with, and the caller's own "warum sehe ich das". Nothing in
 * it knows the type beyond its label - the type-specific parts come in as slots. Rendered only for
 * MANAGER and above, who may change the findability and give rights.
 */
export default function AssetDistributionSection({
  assetType,
  assetId,
  assetName,
  reach,
  listed,
  onSave,
  listedCap,
  capControl,
  grantsTypeSection,
}: AssetDistributionSectionProps) {
  const [draft, setDraft] = useState<boolean | null>(null)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [grantsDialogOpen, setGrantsDialogOpen] = useState(false)
  const [derivationShown, setDerivationShown] = useState(false)

  const noun = assetTypeLabel(assetType)
  const draftListed = draft ?? listed
  const changed = draftListed !== listed
  const idPrefix = `asset-distribution-${assetType.toLowerCase()}`

  async function handleSave() {
    setError(null)
    setSaving(true)
    try {
      await onSave(draftListed)
      setDraft(null)
    } catch (err) {
      // The reach is the one thing an open succession freezes (ADR-0036/6): the refusal names the
      // way out as well.
      setError(successionAwareMessage(err, 'Freigabe konnte nicht gespeichert werden'))
    } finally {
      setSaving(false)
    }
  }

  return (
    <>
      <PageSection
        title="Freigabe"
        description={`Wie weit diese ${noun} reicht, wer sie lesen oder bearbeiten darf und in welchen Spaces sie bereitsteht.`}
        action={
          <Button
            variant="outlined"
            size="small"
            onClick={() => setGrantsDialogOpen(true)}
            sx={{ flexShrink: 0 }}
          >
            Rechte verwalten
          </Button>
        }
      >
        <Stack spacing={2}>
          {error && (
            <Alert severity="error" onClose={() => setError(null)}>
              {error}
            </Alert>
          )}
          <Box>
            <FieldLabel id={`${idPrefix}-reach-label`}>Reichweite</FieldLabel>
            <Typography
              id={`${idPrefix}-reach`}
              aria-labelledby={`${idPrefix}-reach-label`}
              sx={{ fontSize: 14 }}
            >
              {assetReachLabel(reach)}
            </Typography>
            <FormHelperText>
              Ergibt sich aus den Berechtigungen — zu ändern über „Rechte verwalten".
            </FormHelperText>
          </Box>
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
              {saving ? 'Wird gespeichert …' : 'Freigabe speichern'}
            </Button>
          </Stack>
          {capControl}

          <Box sx={{ pt: 1, borderTop: 1, borderColor: 'divider' }}>
            <Typography variant="subtitle2" component="h3" sx={{ mb: 1 }}>
              Bereitgestellt in
            </Typography>
            <AssetSpacesList
              key={`${assetType}-${assetId}`}
              assetType={assetType}
              assetId={assetId}
            />
          </Box>
        </Stack>
      </PageSection>

      {/* ADR-0036, Entscheidung 9: flat and shown as flat - every person sees their own way to
          this asset. Asked for on demand: the derivation costs a request of its own. */}
      <PageSection
        title={`Warum sehe ich diese ${noun}?`}
        description="Ihr eigener Weg zur wirksamen Rolle. Ohne Vollmacht, ohne Protokoll."
      >
        {derivationShown ? (
          <AccessDerivation target={{ kind: 'asset', assetType, assetId }} />
        ) : (
          <Button size="small" onClick={() => setDerivationShown(true)}>
            Herleitung anzeigen
          </Button>
        )}
      </PageSection>

      <AssetGrantsDialog
        open={grantsDialogOpen}
        assetType={assetType}
        assetId={assetId}
        assetName={assetName}
        onClose={() => setGrantsDialogOpen(false)}
        typeSection={grantsTypeSection}
      />
    </>
  )
}
