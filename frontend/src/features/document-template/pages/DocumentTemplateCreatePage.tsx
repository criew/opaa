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
  masterDataReview,
  sharingReview,
  type PendingGrant,
} from '@/features/asset'
import { confirmAction } from '@/shared/stores/confirmStore'
import { notify } from '@/shared/stores/notificationStore'
import { useMyCapabilities } from '@/shared/session/useMyCapabilities'
import { useMyGroups } from '@/features/principal'
import { capabilityMissingMessage } from '@/shared/utils/labels'
import { CATALOG_ROUTE, documentTemplateRoute } from '@/shared/routes'
import type { AssetOwnerType, GroupListResponse } from '@/shared/api/types'
import { createDocumentTemplate } from '../api/documentTemplateApi'
import TemplateFilePicker from '../components/TemplateFilePicker'

const STEPS = [STEP_MASTER_DATA, 'Freigaben', STEP_REVIEW] as const
const STEP_SHARING = 1

/**
 * The creation wizard of a document template: Stammdaten with name, description and the Word
 * file, Freigaben (owner and rights), Prüfen - the steps of the other asset wizards. The file is
 * checked only on creation; a rejection names what is wrong with it.
 */
export default function DocumentTemplateCreatePage() {
  const navigate = useNavigate()
  const { isMissing } = useMyCapabilities()
  const myGroups = useMyGroups()

  const [activeStep, setActiveStep] = useState(0)
  const [returnToReview, setReturnToReview] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [name, setName] = useState('')
  const nameCheck = useFieldError('Name fehlt')
  const [description, setDescription] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const fileCheck = useFieldError<HTMLButtonElement>('Word-Datei fehlt')
  const [ownerType, setOwnerType] = useState<AssetOwnerType>('USER')
  const [selectedGroup, setSelectedGroup] = useState<GroupListResponse | null>(null)
  const groupCheck = useFieldError('Bitte eine Gruppe auswählen')
  const [pendingGrants, setPendingGrants] = useState<PendingGrant[]>([])

  const missingCapability = isMissing('CREATE_TEMPLATE')
    ? capabilityMissingMessage('CREATE_TEMPLATE')
    : null
  const isDirty =
    name.trim() !== '' || description.trim() !== '' || file !== null || pendingGrants.length > 0

  const handleCancel = async () => {
    if (isDirty) {
      const confirmed = await confirmAction({
        question: 'Eingaben verwerfen und den Assistenten verlassen?',
        confirmLabel: 'Verwerfen',
        tone: 'caution',
      })
      if (!confirmed) return
    }
    navigate(CATALOG_ROUTE)
  }

  const handleNext = () => {
    if (activeStep === 0) {
      // both fields report at once; the focus goes to the first one missing
      const fileThere = fileCheck.check(file !== null)
      const nameThere = nameCheck.check(name.trim() !== '')
      if (!nameThere || !fileThere) return
    }
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
    if (!file || submitting) return
    setSubmitting(true)
    setError(null)
    try {
      const created = await createDocumentTemplate({
        file,
        name: name.trim(),
        description: description.trim() || null,
        ownerType,
        ownerId: ownerType === 'GROUP' ? (selectedGroup?.id ?? null) : null,
      })
      await applyPendingGrantsAfterCreation('DOCUMENT_TEMPLATE', created.id, pendingGrants)
      notify(`„${name.trim()}“ wurde angelegt.`, 'success')
      navigate(documentTemplateRoute(created.id))
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Die Vorlage konnte nicht angelegt werden')
      setSubmitting(false)
    }
  }

  const sharing = {
    ownerType,
    selectedGroup,
    pendingGrants,
    onChangeSharing: () => changeAt(STEP_SHARING),
  }

  return (
    <WizardLayout
      object="Neue Vorlage"
      steps={STEPS}
      activeStep={activeStep}
      failed={nameCheck.error !== null || fileCheck.error !== null || groupCheck.error !== null}
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
      createAnnouncement="Vorlage wird angelegt"
      onCancel={() => void handleCancel()}
      onBack={() => {
        setReturnToReview(false)
        setActiveStep((step) => step - 1)
      }}
      onNext={handleNext}
      onCreate={() => void handleCreate()}
    >
      {activeStep === 0 && (
        <>
          <AssetNameFields
            idPrefix="document-template-create"
            name={name}
            onNameChange={(next) => {
              nameCheck.clear()
              setName(next)
            }}
            nameError={nameCheck.error}
            nameInputRef={nameCheck.ref}
            description={description}
            onDescriptionChange={setDescription}
            namePlaceholder="z. B. Vermerk Referat 50"
          />
          <TemplateFilePicker
            file={file}
            onFileChange={(next) => {
              fileCheck.clear()
              setFile(next)
            }}
            error={fileCheck.error}
            buttonRef={fileCheck.ref}
          />
        </>
      )}
      {activeStep === STEP_SHARING && (
        <>
          <Typography variant="body2" sx={{ color: 'text.secondary' }}>
            Eine Gruppe als Eigentümerin übersteht jeden Personalwechsel — für Vorlagen, die ein
            Referat gemeinsam pflegt, ist sie die haltbarere Wahl.
          </Typography>
          <AssetOwnerFields
            idPrefix="document-template-create"
            assetType="DOCUMENT_TEMPLATE"
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
          entries={[
            ...masterDataReview({ name, description, onChangeMasterData: () => changeAt(0) }),
            { label: 'Word-Datei', value: file?.name ?? '', onChange: () => changeAt(0) },
            ...sharingReview(sharing),
          ]}
        />
      )}
    </WizardLayout>
  )
}
