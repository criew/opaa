import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import { useNavigate } from 'react-router'
import PageHeading from '@/shared/ui/a11y/PageHeading'
import WizardStepBar from '@/shared/ui/wizard/WizardStepBar'
import {
  AssetNameFields,
  AssetOwnerFields,
  AssetRightsFields,
  applyPendingGrantsAfterCreation,
  type PendingGrant,
} from '@/features/asset'
import { confirmAction } from '@/shared/stores/confirmStore'
import { useMyCapabilities } from '@/shared/session/useMyCapabilities'
import { useMyGroups } from '@/features/principal'
import { capabilityMissingMessage } from '@/shared/utils/labels'
import { CATALOG_ROUTE, documentTemplateRoute } from '@/shared/routes'
import type { AssetOwnerType, GroupListResponse } from '@/shared/api/types'
import { createDocumentTemplate } from '../api/documentTemplateApi'
import TemplateFilePicker from '../components/TemplateFilePicker'
import { fontSize } from '@/shared/theme/tokens'

const STEPS = ['Stammdaten', 'Eigentümer', 'Rechte'] as const
const STEP_TITLES = ['Stammdaten', 'Wem gehört die Vorlage?', 'Rechte'] as const

/**
 * The creation wizard of a document template: name, description and the Word file, then owner
 * and rights - the steps of the other asset wizards. The file is checked only on creation; a
 * rejection names what is wrong with it.
 */
export default function DocumentTemplateCreatePage() {
  const navigate = useNavigate()
  const { isMissing } = useMyCapabilities()
  const myGroups = useMyGroups()

  const [activeStep, setActiveStep] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [ownerType, setOwnerType] = useState<AssetOwnerType>('USER')
  const [selectedGroup, setSelectedGroup] = useState<GroupListResponse | null>(null)
  const [pendingGrants, setPendingGrants] = useState<PendingGrant[]>([])

  const missingCapability = isMissing('CREATE_TEMPLATE')
    ? capabilityMissingMessage('CREATE_TEMPLATE')
    : null
  const isDirty =
    name.trim() !== '' || description.trim() !== '' || file !== null || pendingGrants.length > 0
  const lastStep = STEPS.length - 1
  const stepComplete = name.trim() !== '' && file !== null

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
    if (activeStep === 1 && ownerType === 'GROUP' && !selectedGroup) {
      setError('Bitte eine Gruppe auswählen')
      return
    }
    setError(null)
    setActiveStep((step) => step + 1)
  }

  const handleCreate = async () => {
    if (!file) return
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
      navigate(documentTemplateRoute(created.id))
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Die Vorlage konnte nicht angelegt werden')
      setSubmitting(false)
    }
  }

  return (
    <Box sx={{ flexGrow: 1, overflowY: 'auto', p: { xs: 2.5, md: 5 } }}>
      <Box sx={{ maxWidth: 720 }}>
        <Typography sx={{ fontSize: fontSize.meta, color: 'text.secondary', mb: 0.5 }}>
          Neue Vorlage
        </Typography>
        <PageHeading title="Neue Vorlage" visuallyHidden />
        <Typography
          component="div"
          sx={{ fontSize: fontSize.wizardTitle, fontWeight: 600, mb: 3 }}
          aria-hidden
        >
          {STEP_TITLES[activeStep]}
        </Typography>
        <WizardStepBar steps={STEPS} active={activeStep} />

        {missingCapability && (
          <Alert severity="info" sx={{ mb: 2 }} id="document-template-create-capability-hint">
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
            <>
              <AssetNameFields
                idPrefix="document-template-create"
                name={name}
                onNameChange={setName}
                description={description}
                onDescriptionChange={setDescription}
                namePlaceholder="z. B. Vermerk Referat 50"
              />
              <TemplateFilePicker file={file} onFileChange={setFile} />
            </>
          )}
          {activeStep === 1 && (
            <>
              <Typography sx={{ fontSize: fontSize.text, color: 'text.secondary' }}>
                Eine Gruppe als Eigentümerin übersteht jeden Personalwechsel — für Vorlagen, die ein
                Referat gemeinsam pflegt, ist sie die haltbarere Wahl.
              </Typography>
              <AssetOwnerFields
                idPrefix="document-template-create"
                assetType="DOCUMENT_TEMPLATE"
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
            <Typography sx={{ fontSize: fontSize.meta, color: 'text.secondary' }}>
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
            <Button variant="contained" onClick={handleNext} disabled={!stepComplete}>
              {activeStep === 1 ? 'Weiter zu Rechten' : 'Weiter'}
            </Button>
          ) : (
            <Button
              variant="contained"
              onClick={() => void handleCreate()}
              disabled={submitting || missingCapability !== null || !stepComplete}
              aria-describedby={
                missingCapability !== null ? 'document-template-create-capability-hint' : undefined
              }
            >
              {submitting ? 'Wird angelegt …' : 'Vorlage anlegen'}
            </Button>
          )}
        </Box>
      </Box>
    </Box>
  )
}
