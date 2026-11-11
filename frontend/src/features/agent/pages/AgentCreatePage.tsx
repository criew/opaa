import { useState } from 'react'
import { useNavigate } from 'react-router'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import FormField from '@/shared/ui/form/FormField'
import WizardLayout from '@/shared/ui/wizard/WizardLayout'
import WizardReview from '@/shared/ui/wizard/WizardReview'
import { useFieldError } from '@/shared/ui/wizard/useFieldError'
import { STEP_MASTER_DATA, STEP_REVIEW } from '@/shared/ui/wizard/wizardSteps'
import {
  AssetOwnerFields,
  AssetRightsFields,
  applyPendingGrantsAfterCreation,
  sharingReview,
  type PendingGrant,
} from '@/features/asset'
import { useMyGroups } from '@/features/principal'
import { useMyCapabilities } from '@/shared/session/useMyCapabilities'
import { capabilityMissingMessage } from '@/shared/utils/labels'
import { confirmAction } from '@/shared/stores/confirmStore'
import { notify } from '@/shared/stores/notificationStore'
import { CATALOG_ROUTE, agentRoute } from '@/shared/routes'
import { apiFieldErrors } from '@/shared/api/apiErrorDetails'
import type { AssetOwnerType, GroupListResponse } from '@/shared/api/types'
import { useAgentStore } from '../stores/agentStore'
import { fieldMessages } from '../components/fieldMessages'
import { NAME_MAX, NAME_PLACEHOLDER, PURPOSE_HELP, PURPOSE_MAX } from '../components/agentTexts'

const ID = 'agent-create'
const STEPS = [STEP_MASTER_DATA, 'Freigaben', STEP_REVIEW] as const
const STEP_SHARING = 1

/**
 * The creation wizard of an agent: Stammdaten (name and purpose), Freigaben (owner and rights),
 * Prüfen. The agent starts empty - no instruction, no library, searching and reading as tools -
 * and is set up on its detail page.
 */
export default function AgentCreatePage() {
  const navigate = useNavigate()
  const create = useAgentStore((s) => s.createAgent)
  const { isMissing } = useMyCapabilities()
  const myGroups = useMyGroups()

  const [activeStep, setActiveStep] = useState(0)
  const [returnToReview, setReturnToReview] = useState(false)
  const [name, setName] = useState('')
  const nameCheck = useFieldError('Name fehlt')
  const [purpose, setPurpose] = useState('')
  const [ownerType, setOwnerType] = useState<AssetOwnerType>('USER')
  const [selectedGroup, setSelectedGroup] = useState<GroupListResponse | null>(null)
  const groupCheck = useFieldError('Gruppe fehlt')
  const [pendingGrants, setPendingGrants] = useState<PendingGrant[]>([])
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const missingCapability = isMissing('CREATE_AGENT')
    ? capabilityMissingMessage('CREATE_AGENT')
    : null
  const isDirty = name.trim() !== '' || purpose.trim() !== '' || pendingGrants.length > 0

  async function handleCancel() {
    if (isDirty) {
      const confirmed = await confirmAction({
        question: 'Eingaben verwerfen?',
        consequence: 'Der Agent wird nicht angelegt; Ihre Eingaben gehen verloren.',
        confirmLabel: 'Verwerfen',
        tone: 'caution',
      })
      if (!confirmed) return
    }
    navigate(CATALOG_ROUTE)
  }

  function handleNext() {
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

  function changeAt(step: number) {
    setReturnToReview(true)
    setError(null)
    setActiveStep(step)
  }

  async function handleCreate() {
    if (submitting) return
    setSubmitting(true)
    setError(null)
    try {
      const id = await create({
        name: name.trim(),
        description: purpose.trim() || null,
        ownerType,
        ownerId: ownerType === 'GROUP' ? (selectedGroup?.id ?? null) : null,
      })
      await applyPendingGrantsAfterCreation('AGENT', id, pendingGrants)
      notify(`„${name.trim()}“ wurde angelegt.`, 'success')
      navigate(agentRoute(id))
    } catch (err) {
      // A refused field takes the server's word to its step and field; anything else stays here.
      const fromServer = fieldMessages(apiFieldErrors(err))
      if (fromServer.name) {
        setActiveStep(0)
        nameCheck.fail(fromServer.name)
      } else if (fromServer.ownerId) {
        setActiveStep(STEP_SHARING)
        groupCheck.fail(fromServer.ownerId)
      } else {
        setError(err instanceof Error ? err.message : 'Der Agent konnte nicht angelegt werden.')
      }
      setReturnToReview(true)
      setSubmitting(false)
    }
  }

  return (
    <WizardLayout
      object="Neuer Agent"
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
      createAnnouncement="Agent wird angelegt"
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
          <Typography variant="body2" sx={{ color: 'text.secondary' }}>
            Der Agent startet leer: ohne Auftrag, mit allem Wissen, das die aufrufende Person lesen
            darf, und mit den Werkzeugen Suchen und Lesen. Auftrag, Wissen und Ablauf richten Sie
            danach auf seiner Seite ein.
          </Typography>
          <Box>
            <FormField
              label="Name"
              id={`${ID}-name`}
              value={name}
              onChange={(e) => {
                nameCheck.clear()
                setName(e.target.value)
              }}
              placeholder={NAME_PLACEHOLDER}
              fullWidth
              required
              inputRef={nameCheck.ref}
              error={nameCheck.error !== null}
              helperText={nameCheck.error}
              slotProps={{ htmlInput: { maxLength: NAME_MAX } }}
            />
          </Box>
          <Box>
            <FormField
              label="Zweck"
              optional
              id={`${ID}-purpose`}
              value={purpose}
              onChange={(e) => setPurpose(e.target.value)}
              multiline
              fullWidth
              helperText={PURPOSE_HELP}
              slotProps={{ htmlInput: { maxLength: PURPOSE_MAX } }}
            />
          </Box>
        </>
      )}
      {activeStep === STEP_SHARING && (
        <>
          <AssetOwnerFields
            idPrefix={ID}
            assetType="AGENT"
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
            { label: 'Name', value: name.trim(), onChange: () => changeAt(0) },
            { label: 'Zweck', value: purpose.trim(), onChange: () => changeAt(0) },
            ...sharingReview({
              ownerType,
              selectedGroup,
              pendingGrants,
              onChangeSharing: () => changeAt(STEP_SHARING),
            }),
          ]}
        />
      )}
    </WizardLayout>
  )
}
