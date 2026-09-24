import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import FormControlLabel from '@mui/material/FormControlLabel'
import Radio from '@mui/material/Radio'
import RadioGroup from '@mui/material/RadioGroup'
import Switch from '@mui/material/Switch'
import Typography from '@mui/material/Typography'
import { alpha } from '@mui/material/styles'
import { useNavigate } from 'react-router'
import PageHeading from '../components/a11y/PageHeading'
import { blue } from '../theme/tokens'
import ConfluenceSourceForm from '../components/library/ConfluenceSourceForm'
import PathSourceForm from '../components/library/PathSourceForm'
import S3SourceForm from '../components/library/S3SourceForm'
import SourceConnectionTest from '../components/library/SourceConnectionTest'
import UrlSourceForm from '../components/library/UrlSourceForm'
import { EMPTY_CONFLUENCE_VALUES, type ConfluenceSourceValues } from '../utils/confluenceSource'
import { EMPTY_S3_VALUES, type S3SourceValues } from '../utils/s3Source'
import WizardStepBar from '../components/wizard/WizardStepBar'
import { confirmAction } from '../stores/confirmStore'
import { useLibraryStore } from '../stores/libraryStore'
import { useIndexingStore } from '../stores/indexingStore'
import { useMyCapabilities } from '../hooks/useMyCapabilities'
import { useMyGroups } from '../hooks/useMyGroups'
import AssetNameFields from '../components/assets/AssetNameFields'
import AssetOwnerFields from '../components/assets/AssetOwnerFields'
import AssetRightsFields from '../components/assets/AssetRightsFields'
import {
  applyPendingGrantsAfterCreation,
  type PendingGrant,
} from '../components/assets/pendingGrants'
import {
  allDocumentSourceTypes,
  capabilityMissingMessage,
  documentSourceTypeConfigKind,
  documentSourceTypeDescription,
  documentSourceTypeLabel,
} from '../utils/labels'
import {
  deriveLibrarySourceConfigPayload,
  validateLibrarySourceFields,
  EMPTY_GENERIC_SOURCE_VALUES,
  type GenericSourceValues,
} from '../utils/librarySourceConfig'
import type {
  Capability,
  DocumentSourceType,
  GroupListResponse,
  AssetOwnerType,
} from '../types/api'

const STEPS = ['Stammdaten', 'Herkunft', 'Rechte'] as const
const STEP_TITLES = ['Stammdaten', 'Woher kommen die Dokumente?', 'Rechte'] as const

/**
 * The library creation wizard (#596, mockup 1e), replacing CreateLibraryDialog. The origin step
 * carries the four source cards with the type-bound connection form and test (#514 invalidation
 * semantics preserved); the rights step sets the distribution level at creation time and queues
 * grants that are applied through the grant API right after the library exists.
 */
export default function LibraryCreatePage() {
  const navigate = useNavigate()
  const createNewLibrary = useLibraryStore((s) => s.createNewLibrary)
  const triggerIndexing = useIndexingStore((s) => s.triggerIndexing)

  const [activeStep, setActiveStep] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  // ADR-0036, Entscheidung 5: uploads and connectors are separate Anlegerechte, because a
  // connector library reaches server paths and stored credentials. A missing right is explained
  // rather than hidden; the backend refuses the same call regardless.
  const { isMissing } = useMyCapabilities()

  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [ownerType, setOwnerType] = useState<AssetOwnerType>('USER')
  const [selectedGroup, setSelectedGroup] = useState<GroupListResponse | null>(null)
  const myGroups = useMyGroups()

  const [sourceType, setSourceType] = useState<DocumentSourceType>('UPLOAD')
  const [generic, setGeneric] = useState<GenericSourceValues>(EMPTY_GENERIC_SOURCE_VALUES)
  const [confluence, setConfluence] = useState<ConfluenceSourceValues>(EMPTY_CONFLUENCE_VALUES)
  const [s3, setS3] = useState<S3SourceValues>(EMPTY_S3_VALUES)
  // Opt-out, not opt-in: whoever just configured a source expects content - the first run (a full
  // reconciliation over the selected spaces) starts right after creation unless switched off.
  const [startFirstRun, setStartFirstRun] = useState(true)

  const requiredCapability: Capability =
    sourceType === 'UPLOAD' ? 'CREATE_LIBRARY' : 'CREATE_CONNECTOR_LIBRARY'
  const missingCapability = isMissing(requiredCapability)
    ? capabilityMissingMessage(requiredCapability)
    : null

  const [pendingGrants, setPendingGrants] = useState<PendingGrant[]>([])

  const configKind = documentSourceTypeConfigKind[sourceType]

  const isDirty =
    name.trim() !== '' ||
    description.trim() !== '' ||
    generic.sourcePath !== '' ||
    generic.sourceUrl !== '' ||
    confluence.sourceUrl !== '' ||
    confluence.sourceProxy !== '' ||
    confluence.sourceInsecureSsl ||
    s3.sourceUrl !== '' ||
    s3.accessKey !== '' ||
    s3.secretKey !== '' ||
    s3.sessionToken !== '' ||
    s3.scopes.some((scope) => scope.bucket !== '' || scope.prefix !== '') ||
    pendingGrants.length > 0

  const handleCancel = async () => {
    if (isDirty) {
      const confirmed = await confirmAction({
        question: 'Eingaben verwerfen und den Assistenten verlassen?',
        confirmLabel: 'Verwerfen',
        tone: 'caution',
      })
      if (!confirmed) return
    }
    navigate('/libraries')
  }

  const handleNext = () => {
    if (activeStep === 0 && ownerType === 'GROUP' && !selectedGroup) {
      setError('Bitte eine Gruppe auswählen')
      return
    }
    if (activeStep === 1) {
      const validationError = validateLibrarySourceFields(sourceType, {
        ...generic,
        confluence,
        s3,
      })
      if (validationError) {
        setError(validationError)
        return
      }
    }
    setError(null)
    setActiveStep((s) => s + 1)
  }

  const handleCreate = async () => {
    setSubmitting(true)
    setError(null)
    try {
      const libraryId = await createNewLibrary({
        name: name.trim(),
        description: description.trim() || undefined,
        ownerType,
        ownerId: ownerType === 'GROUP' ? (selectedGroup?.id ?? undefined) : undefined,
        sourceType,
        ...deriveLibrarySourceConfigPayload(sourceType, {
          ...generic,
          confluence,
          s3,
        }),
      })
      await applyPendingGrantsAfterCreation('KNOWLEDGE_LIBRARY', libraryId, pendingGrants)
      if ((configKind === 'confluence' || configKind === 's3') && startFirstRun) {
        // Awaited so the run is already in the indexing store when the detail page mounts and its
        // progress strip picks it up. triggerIndexing never throws - a failure surfaces through
        // the global indexing snackbar, and the detail page still offers "Jetzt indizieren".
        await triggerIndexing(libraryId, sourceType)
      }
      navigate(`/libraries/${libraryId}`)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Bibliothek konnte nicht erstellt werden')
      setSubmitting(false)
    }
  }

  return (
    <Box sx={{ flexGrow: 1, overflowY: 'auto', p: { xs: 2.5, md: 5 } }}>
      <Box sx={{ maxWidth: 720 }}>
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mb: 0.5 }}>
          Neue Wissensbibliothek
        </Typography>
        <PageHeading title="Neue Wissensbibliothek" visuallyHidden />
        <Typography component="div" sx={{ fontSize: 26, fontWeight: 600, mb: 3 }} aria-hidden>
          {STEP_TITLES[activeStep]}
        </Typography>
        <WizardStepBar steps={STEPS} active={activeStep} />

        {missingCapability && (
          <Alert severity="info" sx={{ mb: 2 }} id="library-create-capability-hint">
            {missingCapability}
          </Alert>
        )}

        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}

        {activeStep === 0 && (
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2.5, maxWidth: 640 }}>
            <AssetNameFields
              idPrefix="library-create"
              name={name}
              onNameChange={setName}
              description={description}
              onDescriptionChange={setDescription}
              namePlaceholder="z. B. Rechtsquellen Soziales"
            />
            <AssetOwnerFields
              idPrefix="library-create"
              assetType="KNOWLEDGE_LIBRARY"
              ownerType={ownerType}
              onOwnerTypeChange={setOwnerType}
              myGroups={myGroups}
              selectedGroup={selectedGroup}
              onSelectedGroupChange={setSelectedGroup}
            />
          </Box>
        )}

        {activeStep === 1 && (
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
            <RadioGroup
              aria-label="Herkunft wählen"
              value={sourceType}
              onChange={(e) => {
                setSourceType(e.target.value as DocumentSourceType)
                setError(null)
              }}
              sx={{
                display: 'grid',
                gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr' },
                gap: '14px',
              }}
            >
              {allDocumentSourceTypes.map((type) => {
                const selected = type === sourceType
                return (
                  <FormControlLabel
                    key={type}
                    value={type}
                    control={<Radio size="small" sx={{ p: 0, mt: '2px' }} />}
                    label={
                      <Box>
                        <Typography sx={{ fontSize: 14.5, fontWeight: 600 }}>
                          {documentSourceTypeLabel(type)}
                        </Typography>
                        <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 0.25 }}>
                          {documentSourceTypeDescription(type)}
                        </Typography>
                      </Box>
                    }
                    sx={{
                      alignItems: 'flex-start',
                      gap: '12px',
                      m: 0,
                      p: 2,
                      border: selected ? 2 : 1,
                      borderColor: selected ? 'primary.main' : 'divider',
                      borderRadius: '10px',
                      bgcolor: selected
                        ? (theme) =>
                            theme.palette.mode === 'dark'
                              ? alpha(theme.palette.primary.main, 0.16)
                              : blue[50]
                        : 'transparent',
                      '&:hover': { borderColor: selected ? 'primary.main' : 'text.disabled' },
                    }}
                  />
                )
              })}
            </RadioGroup>

            {configKind === 'none' && (
              <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
                Dokumente laden Sie nach dem Anlegen auf der Detailseite hoch — einzeln oder
                gebündelt.
              </Typography>
            )}

            {configKind === 'confluence' && (
              <Box sx={{ maxWidth: 640 }}>
                <ConfluenceSourceForm
                  mode="create"
                  idPrefix="library-create-confluence"
                  values={confluence}
                  onChange={(patch) => {
                    setConfluence((prev) => ({ ...prev, ...patch }))
                    setError(null)
                  }}
                />
                <FormControlLabel
                  sx={{ mt: 2 }}
                  control={
                    <Switch
                      checked={startFirstRun}
                      onChange={(e) => setStartFirstRun(e.target.checked)}
                    />
                  }
                  label="Erste Indizierung sofort nach dem Anlegen starten"
                />
                <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 0.5 }}>
                  {startFirstRun
                    ? 'Der erste Lauf ist ein Vollabgleich über alle ausgewählten Spaces; sein Stand bleibt auf der Detailseite sichtbar.'
                    : 'Ohne Sofortstart beginnt die Indizierung erst über „Jetzt indizieren“ auf der Detailseite oder über den Zeitplan.'}
                </Typography>
              </Box>
            )}

            {configKind === 's3' && (
              <Box sx={{ maxWidth: 640 }}>
                <S3SourceForm
                  mode="create"
                  idPrefix="library-create-s3"
                  values={s3}
                  onChange={(patch) => {
                    setS3((prev) => ({ ...prev, ...patch }))
                    setError(null)
                  }}
                />
                <FormControlLabel
                  sx={{ mt: 2 }}
                  control={
                    <Switch
                      checked={startFirstRun}
                      onChange={(e) => setStartFirstRun(e.target.checked)}
                    />
                  }
                  label="Erste Indizierung sofort nach dem Anlegen starten"
                />
                <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 0.5 }}>
                  {startFirstRun
                    ? 'Der erste Lauf ist ein Vollabgleich über alle Geltungsbereiche; sein Stand bleibt auf der Detailseite sichtbar.'
                    : 'Ohne Sofortstart beginnt die Indizierung erst über „Jetzt indizieren“ auf der Detailseite oder über den Zeitplan.'}
                </Typography>
              </Box>
            )}

            {configKind === 'path' && (
              <Box sx={{ maxWidth: 640 }}>
                <PathSourceForm
                  mode="create"
                  idPrefix="library-create"
                  values={generic}
                  onChange={(patch) => setGeneric((prev) => ({ ...prev, ...patch }))}
                />
              </Box>
            )}

            {configKind === 'url' && (
              <Box sx={{ maxWidth: 640 }}>
                <UrlSourceForm
                  mode="create"
                  sourceType={sourceType}
                  idPrefix="library-create"
                  values={generic}
                  onChange={(patch) => setGeneric((prev) => ({ ...prev, ...patch }))}
                />
              </Box>
            )}

            {(configKind === 'path' || configKind === 'url') && (
              <Box sx={{ maxWidth: 640 }}>
                <SourceConnectionTest sourceType={sourceType} values={generic} />
                <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 2 }}>
                  Der erste Lauf startet nach dem Anlegen; sein Stand bleibt auf der Detailseite
                  sichtbar.
                </Typography>
              </Box>
            )}
          </Box>
        )}

        {activeStep === 2 && (
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2.5, maxWidth: 640 }}>
            <AssetRightsFields
              idPrefix="library-create"
              pendingGrants={pendingGrants}
              onPendingGrantsChange={setPendingGrants}
            />
          </Box>
        )}

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
          {activeStep === 2 && pendingGrants.length > 0 && (
            <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>
              {pendingGrants.length === 1
                ? '1 Freigabe vorgemerkt'
                : `${pendingGrants.length} Freigaben vorgemerkt`}
            </Typography>
          )}
          {activeStep > 0 && (
            <Button
              variant="outlined"
              onClick={() => setActiveStep((s) => s - 1)}
              disabled={submitting}
            >
              Zurück
            </Button>
          )}
          {activeStep < STEPS.length - 1 ? (
            <Button variant="contained" onClick={handleNext} disabled={name.trim() === ''}>
              {activeStep === 1 ? 'Weiter zu Rechten' : 'Weiter'}
            </Button>
          ) : (
            <Button
              variant="contained"
              onClick={() => void handleCreate()}
              disabled={submitting || missingCapability !== null || name.trim() === ''}
              aria-describedby={
                missingCapability !== null ? 'library-create-capability-hint' : undefined
              }
            >
              {submitting ? 'Wird angelegt …' : 'Bibliothek anlegen'}
            </Button>
          )}
        </Box>
      </Box>
    </Box>
  )
}
