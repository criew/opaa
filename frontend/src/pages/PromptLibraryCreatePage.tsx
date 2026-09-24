import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import { useNavigate } from 'react-router'
import PageHeading from '../components/a11y/PageHeading'
import WizardStepBar from '../components/wizard/WizardStepBar'
import AssetNameFields from '../components/assets/AssetNameFields'
import AssetOwnerFields from '../components/assets/AssetOwnerFields'
import AssetRightsFields from '../components/assets/AssetRightsFields'
import {
  applyPendingGrantsAfterCreation,
  type PendingGrant,
} from '../components/assets/pendingGrants'
import { confirmAction } from '../stores/confirmStore'
import { usePromptLibraryStore } from '../stores/promptLibraryStore'
import { useMyCapabilities } from '../hooks/useMyCapabilities'
import { useMyGroups } from '../hooks/useMyGroups'
import { capabilityMissingMessage } from '../utils/labels'
import { promptLibraryRoute } from '../routes'
import type { AssetOwnerType, GroupListResponse } from '../types/api'

const STEPS = ['Stammdaten', 'Eigentümer', 'Rechte'] as const
const STEP_TITLES = ['Stammdaten', 'Wem gehört die Prompt-Bibliothek?', 'Rechte'] as const

/**
 * The creation wizard of a prompt library: master data, owner (person or group), rights. The
 * steps are the knowledge library wizard's own components; only the origin step is left out,
 * since a prompt library has no source. `listed` starts off - listing is a deliberate act.
 */
export default function PromptLibraryCreatePage() {
  const navigate = useNavigate()
  const createLibrary = usePromptLibraryStore((s) => s.createLibrary)
  const { isMissing } = useMyCapabilities()
  const myGroups = useMyGroups()

  const [activeStep, setActiveStep] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [ownerType, setOwnerType] = useState<AssetOwnerType>('USER')
  const [selectedGroup, setSelectedGroup] = useState<GroupListResponse | null>(null)
  const [listed, setListed] = useState(false)
  const [pendingGrants, setPendingGrants] = useState<PendingGrant[]>([])

  const missingCapability = isMissing('CREATE_PROMPT_LIBRARY')
    ? capabilityMissingMessage('CREATE_PROMPT_LIBRARY')
    : null
  const isDirty = name.trim() !== '' || description.trim() !== '' || pendingGrants.length > 0
  const lastStep = STEPS.length - 1

  const handleCancel = async () => {
    if (isDirty) {
      const confirmed = await confirmAction({
        question: 'Eingaben verwerfen und den Assistenten verlassen?',
        confirmLabel: 'Verwerfen',
        tone: 'caution',
      })
      if (!confirmed) return
    }
    navigate('/prompts')
  }

  const handleNext = () => {
    if (activeStep === 1 && ownerType === 'GROUP' && !selectedGroup) {
      setError('Bitte eine Gruppe auswählen')
      return
    }
    setError(null)
    setActiveStep((step) => step + 1)
  }

  const handleCreate = async () => {
    setSubmitting(true)
    setError(null)
    try {
      const id = await createLibrary({
        name: name.trim(),
        description: description.trim() || null,
        ownerType,
        ownerId: ownerType === 'GROUP' ? (selectedGroup?.id ?? null) : null,
        listed,
      })
      await applyPendingGrantsAfterCreation('PROMPT_LIBRARY', id, pendingGrants)
      navigate(promptLibraryRoute(id))
    } catch (err) {
      setError(
        err instanceof Error ? err.message : 'Die Prompt-Bibliothek konnte nicht angelegt werden',
      )
      setSubmitting(false)
    }
  }

  return (
    <Box sx={{ flexGrow: 1, overflowY: 'auto', p: { xs: 2.5, md: 5 } }}>
      <Box sx={{ maxWidth: 720 }}>
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mb: 0.5 }}>
          Neue Prompt-Bibliothek
        </Typography>
        <PageHeading title="Neue Prompt-Bibliothek" visuallyHidden />
        <Typography component="div" sx={{ fontSize: 26, fontWeight: 600, mb: 3 }} aria-hidden>
          {STEP_TITLES[activeStep]}
        </Typography>
        <WizardStepBar steps={STEPS} active={activeStep} />

        {missingCapability && (
          <Alert severity="info" sx={{ mb: 2 }} id="prompt-library-create-capability-hint">
            {missingCapability}
          </Alert>
        )}
        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}

        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2.5, maxWidth: 640 }}>
          {activeStep === 0 && (
            <AssetNameFields
              idPrefix="prompt-library-create"
              name={name}
              onNameChange={setName}
              description={description}
              onDescriptionChange={setDescription}
              namePlaceholder="z. B. Formulierungshilfen Referat 50"
            />
          )}
          {activeStep === 1 && (
            <>
              <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
                Eine Gruppe als Eigentümerin übersteht jeden Personalwechsel — für Prompts, die ein
                Referat gemeinsam pflegt, ist sie die haltbarere Wahl.
              </Typography>
              <AssetOwnerFields
                idPrefix="prompt-library-create"
                assetType="PROMPT_LIBRARY"
                ownerType={ownerType}
                onOwnerTypeChange={setOwnerType}
                myGroups={myGroups}
                selectedGroup={selectedGroup}
                onSelectedGroupChange={setSelectedGroup}
              />
            </>
          )}
          {activeStep === 2 && (
            <AssetRightsFields
              idPrefix="prompt-library-create"
              listed={{ value: listed, onChange: setListed }}
              pendingGrants={pendingGrants}
              onPendingGrantsChange={setPendingGrants}
            />
          )}
        </Box>

        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            gap: 1.5,
            mt: 4,
            pt: 2,
            borderTop: 1,
            borderColor: 'divider',
          }}
        >
          <Button variant="text" onClick={() => void handleCancel()} disabled={submitting}>
            Abbrechen
          </Button>
          <Box sx={{ flex: 1 }} />
          {activeStep === lastStep && pendingGrants.length > 0 && (
            <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>
              {pendingGrants.length === 1
                ? '1 Freigabe vorgemerkt'
                : `${pendingGrants.length} Freigaben vorgemerkt`}
            </Typography>
          )}
          {activeStep > 0 && (
            <Button
              variant="outlined"
              onClick={() => setActiveStep((step) => step - 1)}
              disabled={submitting}
            >
              Zurück
            </Button>
          )}
          {activeStep < lastStep ? (
            <Button variant="contained" onClick={handleNext} disabled={name.trim() === ''}>
              {activeStep === 1 ? 'Weiter zu Rechten' : 'Weiter'}
            </Button>
          ) : (
            <Button
              variant="contained"
              onClick={() => void handleCreate()}
              disabled={submitting || missingCapability !== null || name.trim() === ''}
              aria-describedby={
                missingCapability !== null ? 'prompt-library-create-capability-hint' : undefined
              }
            >
              {submitting ? 'Wird angelegt …' : 'Prompt-Bibliothek anlegen'}
            </Button>
          )}
        </Box>
      </Box>
    </Box>
  )
}
