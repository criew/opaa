import { useState, type ReactNode } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import FormControl from '@mui/material/FormControl'
import FormControlLabel from '@mui/material/FormControlLabel'
import FormHelperText from '@mui/material/FormHelperText'
import MenuItem from '@mui/material/MenuItem'
import Select from '@mui/material/Select'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import type { AssetType, AssetVisibility } from '../../types/api'
import PageSection from '../PageSection'
import FieldLabel from '../wizard/FieldLabel'
import AssetGrantsDialog from '../permissions/AssetGrantsDialog'
import { successionAwareMessage } from '../succession/successionConflict'
import AssetAccessDerivationSection from './AssetAccessDerivationSection'
import AssetSpacesList from './AssetSpacesList'
import {
  assetTypeLabel,
  libraryVisibilities,
  libraryVisibilityDescription,
  libraryVisibilityLabel,
} from '../../utils/labels'

/**
 * Mirrors the backend's `AssetVisibility#exceeds`: whether `option` reaches further than the
 * cap - `false` while no cap is known, matching the backend's own "nothing to check" default.
 */
function exceedsVisibilityCap(option: AssetVisibility, cap: AssetVisibility | null | undefined) {
  if (!cap) return false
  return libraryVisibilities.indexOf(option) > libraryVisibilities.indexOf(cap)
}

export interface AssetDistributionSectionProps {
  assetType: AssetType
  assetId: string
  assetName: string
  /** The saved values; the section keeps its own draft until "Freigabe speichern". */
  visibility: AssetVisibility
  listed: boolean
  /** Saves both fields together; a rejection is shown in the section, succession-aware. */
  onSave: (visibility: AssetVisibility, listed: boolean) => Promise<void>
  /** A ceiling on the reach set elsewhere; options above it are disabled and explained. */
  visibilityCap?: AssetVisibility | null
  listedCap?: boolean | null
  /** Type-specific control below the fields, e.g. the connector library's share cap. */
  capControl?: ReactNode
  /** Type-specific part of the rights dialog, e.g. a library's release for Fremdzugänge. */
  grantsTypeSection?: ReactNode
}

/**
 * The one release section of every asset type: distribution level and findability, the rights
 * dialog, the spaces the asset is associated with, and the caller's own "warum sehe ich das".
 * Nothing in it knows the type beyond its label - the type-specific parts come in as slots.
 * Rendered only for MANAGER and above, who may change the reach and give rights.
 */
export default function AssetDistributionSection({
  assetType,
  assetId,
  assetName,
  visibility,
  listed,
  onSave,
  visibilityCap,
  listedCap,
  capControl,
  grantsTypeSection,
}: AssetDistributionSectionProps) {
  const [draft, setDraft] = useState<{ visibility: AssetVisibility; listed: boolean } | null>(null)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [grantsDialogOpen, setGrantsDialogOpen] = useState(false)

  const noun = assetTypeLabel(assetType)
  const draftVisibility = draft?.visibility ?? visibility
  const draftListed = draft?.listed ?? listed
  const changed = draftVisibility !== visibility || draftListed !== listed
  const idPrefix = `asset-distribution-${assetType.toLowerCase()}`

  async function handleSave() {
    setError(null)
    setSaving(true)
    try {
      await onSave(draftVisibility, draftListed)
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
          <FormControl size="small" fullWidth>
            <FieldLabel id={`${idPrefix}-visibility-label`}>Verteilungsstufe</FieldLabel>
            <Select
              labelId={`${idPrefix}-visibility-label`}
              value={draftVisibility}
              onChange={(e) =>
                setDraft({ visibility: e.target.value as AssetVisibility, listed: draftListed })
              }
            >
              {libraryVisibilities.map((option) => (
                <MenuItem
                  key={option}
                  value={option}
                  disabled={exceedsVisibilityCap(option, visibilityCap)}
                >
                  {libraryVisibilityLabel(option)}
                </MenuItem>
              ))}
            </Select>
            <FormHelperText>{libraryVisibilityDescription(draftVisibility)}</FormHelperText>
            {visibilityCap && visibilityCap !== 'ORGANIZATION' && (
              <FormHelperText>
                Die Systemverwaltung hat die Freigabe dieser {noun} auf „
                {libraryVisibilityLabel(visibilityCap)}“ begrenzt.
              </FormHelperText>
            )}
          </FormControl>
          <Box>
            <FormControlLabel
              control={
                <Checkbox
                  checked={draftListed}
                  disabled={listedCap === false}
                  onChange={(e) =>
                    setDraft({ visibility: draftVisibility, listed: e.target.checked })
                  }
                />
              }
              label="Im Katalog auffindbar"
            />
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
              Zuordnungen
            </Typography>
            <AssetSpacesList
              key={`${assetType}-${assetId}`}
              assetType={assetType}
              assetId={assetId}
            />
          </Box>
        </Stack>
      </PageSection>

      <AssetAccessDerivationSection assetType={assetType} assetId={assetId} />

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
