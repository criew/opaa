import { useState } from 'react'
import Typography from '@mui/material/Typography'
import { useNavigate } from 'react-router'
import WizardLayout from '@/shared/ui/wizard/WizardLayout'
import WizardReview from '@/shared/ui/wizard/WizardReview'
import { useFieldError } from '@/shared/ui/wizard/useFieldError'
import { STEP_MASTER_DATA, STEP_REVIEW } from '@/shared/ui/wizard/wizardSteps'
import {
  AssetNameFields,
  AssetOwnerFields,
  AssetRightsFields,
  applyPendingGrantsAfterCreation,
  assetCreationReview,
  type PendingGrant,
} from '@/features/asset'
import { confirmAction } from '@/shared/stores/confirmStore'
import { notify } from '@/shared/stores/notificationStore'
import { usePromptLibraryStore } from '../stores/promptLibraryStore'
import { useMyCapabilities } from '@/shared/session/useMyCapabilities'
import { useMyGroups } from '@/features/principal'
import { capabilityMissingMessage } from '@/shared/utils/labels'
import { CATALOG_ROUTE, promptLibraryRoute } from '@/shared/routes'
import type { AssetOwnerType, GroupListResponse } from '@/shared/api/types'

const STEPS = [STEP_MASTER_DATA, 'Freigaben', STEP_REVIEW] as const
const STEP_SHARING = 1

/**
 * The creation wizard of a prompt library: Stammdaten, Freigaben (owner and rights), Prüfen. The
 * steps are the knowledge library wizard's own components; only the source steps are left out,
 * since a prompt library has no source.
 */
export default function PromptLibraryCreatePage() {
  const navigate = useNavigate()
  const createLibrary = usePromptLibraryStore((s) => s.createLibrary)
  const { isMissing } = useMyCapabilities()
  const myGroups = useMyGroups()

  const [activeStep, setActiveStep] = useState(0)
  const [returnToReview, setReturnToReview] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [name, setName] = useState('')
  const nameCheck = useFieldError('Name fehlt')
  const [description, setDescription] = useState('')
  const [ownerType, setOwnerType] = useState<AssetOwnerType>('USER')
  const [selectedGroup, setSelectedGroup] = useState<GroupListResponse | null>(null)
  const groupCheck = useFieldError('Bitte eine Gruppe auswählen')
  const [pendingGrants, setPendingGrants] = useState<PendingGrant[]>([])

  const missingCapability = isMissing('CREATE_PROMPT_LIBRARY')
    ? capabilityMissingMessage('CREATE_PROMPT_LIBRARY')
    : null
  const isDirty = name.trim() !== '' || description.trim() !== '' || pendingGrants.length > 0

  const handleCancel = async () => {
    if (isDirty) {
      const confirmed = await confirmAction({
        question: 'Eingaben verwerfen?',
        consequence: 'Der Assistent schließt, ohne etwas anzulegen; Ihre Eingaben gehen verloren.',
        confirmLabel: 'Verwerfen',
        tone: 'caution',
      })
      if (!confirmed) return
    }
    navigate(CATALOG_ROUTE)
  }

  const handleNext = () => {
    if (activeStep === 0 && !nameCheck.check(name.trim() !== '')) return
    if (
      activeStep === STEP_SHARING &&
      !groupCheck.check(ownerType !== 'GROUP' || selectedGroup !== null)
    ) {
      return
    }
    setActiveStep((step) => (returnToReview ? STEPS.length - 1 : step + 1))
    setReturnToReview(false)
  }

  const changeAt = (step: number) => {
    setReturnToReview(true)
    setError(null)
    setActiveStep(step)
  }

  const handleCreate = async () => {
    if (submitting) return
    setSubmitting(true)
    setError(null)
    try {
      const id = await createLibrary({
        name: name.trim(),
        description: description.trim() || null,
        ownerType,
        ownerId: ownerType === 'GROUP' ? (selectedGroup?.id ?? null) : null,
      })
      await applyPendingGrantsAfterCreation('PROMPT_LIBRARY', id, pendingGrants)
      notify(`„${name.trim()}“ wurde angelegt.`, 'success')
      navigate(promptLibraryRoute(id))
    } catch (err) {
      setError(
        err instanceof Error ? err.message : 'Die Prompt-Bibliothek konnte nicht angelegt werden',
      )
      setSubmitting(false)
    }
  }

  return (
    <WizardLayout
      object="Neue Prompt-Bibliothek"
      steps={STEPS}
      activeStep={activeStep}
      failed={nameCheck.error !== null || groupCheck.error !== null}
      createBlockedBy={missingCapability}
      error={error}
      footerNote={
        activeStep === STEP_SHARING && pendingGrants.length > 0
          ? pendingGrants.length === 1
            ? '1 Freigabe vorgemerkt'
            : `${pendingGrants.length} Freigaben vorgemerkt`
          : null
      }
      footerSeparated={activeStep !== STEPS.length - 1}
      submitting={submitting}
      createAnnouncement="Prompt-Bibliothek wird angelegt"
      onCancel={() => void handleCancel()}
      onBack={() => {
        setReturnToReview(false)
        setActiveStep((step) => step - 1)
      }}
      onNext={handleNext}
      onCreate={() => void handleCreate()}
    >
      {activeStep === 0 && (
        <AssetNameFields
          idPrefix="prompt-library-create"
          name={name}
          onNameChange={(next) => {
            nameCheck.clear()
            setName(next)
          }}
          nameError={nameCheck.error}
          nameInputRef={nameCheck.ref}
          description={description}
          onDescriptionChange={setDescription}
          namePlaceholder="z. B. Formulierungshilfen Referat 50"
        />
      )}
      {activeStep === STEP_SHARING && (
        <>
          <Typography variant="body2" sx={{ color: 'text.secondary' }}>
            Eine Gruppe als Eigentümerin übersteht jeden Personalwechsel — für Prompts, die ein
            Referat gemeinsam pflegt, ist sie die haltbarere Wahl.
          </Typography>
          <AssetOwnerFields
            idPrefix="prompt-library-create"
            assetType="PROMPT_LIBRARY"
            ownerType={ownerType}
            onOwnerTypeChange={(next) => {
              groupCheck.clear()
              setOwnerType(next)
            }}
            myGroups={myGroups}
            selectedGroup={selectedGroup}
            onSelectedGroupChange={(group) => {
              groupCheck.clear()
              setSelectedGroup(group)
            }}
            groupError={groupCheck.error}
            groupInputRef={groupCheck.ref}
          />
          <AssetRightsFields
            pendingGrants={pendingGrants}
            onPendingGrantsChange={setPendingGrants}
          />
        </>
      )}
      {activeStep === STEPS.length - 1 && (
        <WizardReview
          entries={assetCreationReview({
            name,
            description,
            ownerType,
            selectedGroup,
            pendingGrants,
            onChangeMasterData: () => changeAt(0),
            onChangeSharing: () => changeAt(STEP_SHARING),
          })}
        />
      )}
    </WizardLayout>
  )
}
