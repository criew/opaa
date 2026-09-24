import { useEffect, useRef, useState } from 'react'
import type { KeyboardEvent as ReactKeyboardEvent } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import FormControlLabel from '@mui/material/FormControlLabel'
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
import LibraryScheduleForm from '../components/library/LibraryScheduleForm'
import SourceTypeIcon from '../components/library/sourceTypeIcon'
import { EMPTY_CONFLUENCE_VALUES, type ConfluenceSourceValues } from '../utils/confluenceSource'
import { EMPTY_S3_VALUES, type S3SourceValues } from '../utils/s3Source'
import {
  scheduleUpdateFrom,
  scheduleValuesFrom,
  validateScheduleValues,
  type ConfluenceFullSyncRhythm,
  type LibraryScheduleValues,
} from '../utils/librarySchedule'
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

/**
 * Die Schritte des Assistenten (#1942): jeder trägt den Namen des Reiters bzw. des Kopfes, den er
 * in der Detailansicht bekommt. Eine Upload-Bibliothek hat keine Quelle und deshalb drei Schritte.
 */
const STEP_ART = 'Art des Wissens'
const STEP_SOURCE = 'Quelle'
const STEP_NAME = 'Name & Beschreibung'
const STEP_SHARING = 'Freigaben'

function stepsFor(sourceType: DocumentSourceType): string[] {
  return sourceType === 'UPLOAD'
    ? [STEP_ART, STEP_NAME, STEP_SHARING]
    : [STEP_ART, STEP_SOURCE, STEP_NAME, STEP_SHARING]
}

const stepHeadings: Record<string, string> = {
  [STEP_ART]: 'Welche Art von Wissen soll hier stehen?',
  [STEP_SOURCE]: 'Woher kommen die Dokumente?',
  [STEP_NAME]: 'Name & Beschreibung',
  [STEP_SHARING]: 'Freigaben',
}

/**
 * Die Vollabgleich-Angabe beim Anlegen: Die Bibliothek hat noch keinen eigenen Rhythmus, und die
 * Vorgabe der Instanz steht erst in ihrer Antwort - leer heißt hier wie dort „Vorgabe der
 * Instanz", und das Formular nennt deren ausgelieferten Wert.
 */
const NEW_CONFLUENCE_RHYTHM: ConfluenceFullSyncRhythm = { intervalDays: null, defaultDays: null }

/** Der Name, den die Quelle selbst schon hergibt - überschreibbar, nie erzwungen. */
function nameFromSource(
  sourceType: DocumentSourceType,
  values: {
    generic: GenericSourceValues
    confluence: ConfluenceSourceValues
    s3: S3SourceValues
  },
): string {
  switch (documentSourceTypeConfigKind[sourceType]) {
    case 'confluence': {
      const first = values.confluence.spaces[0]
      if (!first) return ''
      return values.confluence.spaces.length === 1 ? (first.name ?? first.key) : ''
    }
    case 's3': {
      const first = values.s3.scopes.find((scope) => scope.bucket.trim() !== '')
      return first ? first.bucket.trim() : ''
    }
    case 'path': {
      const segments = values.generic.sourcePath.split(/[\\/]+/).filter(Boolean)
      return segments.length > 0 ? segments[segments.length - 1] : ''
    }
    case 'url': {
      try {
        return new URL(values.generic.sourceUrl).hostname
      } catch {
        return ''
      }
    }
    default:
      return ''
  }
}

/**
 * Der Anlage-Assistent für Wissensbibliotheken (#1942, Zielentwurf aus #1927). Jeder Schritt
 * entspricht einem Reiter der Detailseite und verwendet dessen Formulare - Quellformulare,
 * Verbindungstest, Zeitplan und die Freigabebausteine sind gemeinsame Komponenten, keine eigenen
 * Felder dieser Seite.
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
  const [nameTouched, setNameTouched] = useState(false)
  const [description, setDescription] = useState('')
  const [ownerType, setOwnerType] = useState<AssetOwnerType>('USER')
  const [selectedGroup, setSelectedGroup] = useState<GroupListResponse | null>(null)
  const myGroups = useMyGroups()

  const [chosenType, setSourceType] = useState<DocumentSourceType>('UPLOAD')
  const [generic, setGeneric] = useState<GenericSourceValues>(EMPTY_GENERIC_SOURCE_VALUES)
  const [confluence, setConfluence] = useState<ConfluenceSourceValues>(EMPTY_CONFLUENCE_VALUES)
  const [s3, setS3] = useState<S3SourceValues>(EMPTY_S3_VALUES)
  const [schedule, setSchedule] = useState<LibraryScheduleValues>(() => scheduleValuesFrom(null))
  // Opt-out, not opt-in: whoever just configured a source expects content - the first run starts
  // right after creation unless switched off. Since #1942 for every connector type, not only
  // Confluence and S3.
  const [startFirstRun, setStartFirstRun] = useState(true)
  const [listed, setListed] = useState(false)
  const [pendingGrants, setPendingGrants] = useState<PendingGrant[]>([])

  /** Die Begründung, warum diese Art hier nicht zu wählen ist - oder `null`, wenn sie es ist. */
  function missingFor(type: DocumentSourceType): string | null {
    const capability: Capability = type === 'UPLOAD' ? 'CREATE_LIBRARY' : 'CREATE_CONNECTOR_LIBRARY'
    return isMissing(capability) ? capabilityMissingMessage(capability) : null
  }

  const selectableTypes = allDocumentSourceTypes.filter((type) => missingFor(type) === null)

  /**
   * Die tatsächlich gewählte Art. Die Anlegerechte kommen erst nach dem ersten Rendern an; was
   * dann gesperrt ist, darf nicht ausgewählt stehen bleiben, sonst führte „Weiter" in einen Pfad,
   * der nie anlegen kann. Die Wahl rückt auf die erste erlaubte Kachel - abgeleitet, nicht in
   * einem Effekt nachgezogen. Ist gar keine erlaubt, bleibt sie stehen, und der Hinweis über der
   * Schrittleiste erklärt, warum am Ende nichts angelegt wird.
   */
  const sourceType =
    missingFor(chosenType) !== null && selectableTypes.length > 0 ? selectableTypes[0] : chosenType

  const steps = stepsFor(sourceType)
  const currentStep = steps[Math.min(activeStep, steps.length - 1)]
  const configKind = documentSourceTypeConfigKind[sourceType]
  const confluenceRhythm = configKind === 'confluence' ? NEW_CONFLUENCE_RHYTHM : undefined

  const requiredCapability: Capability =
    sourceType === 'UPLOAD' ? 'CREATE_LIBRARY' : 'CREATE_CONNECTOR_LIBRARY'
  const missingCapability = isMissing(requiredCapability)
    ? capabilityMissingMessage(requiredCapability)
    : null

  /**
   * Pfeiltasten wählen innerhalb der Kachelgruppe die nächste bzw. vorherige *wählbare* Kachel und
   * ziehen den Fokus mit (WAI-ARIA „radio group"); Pos1/Ende springen an die Enden. Eine gesperrte
   * Kachel wird dabei übersprungen, statt den Fokus zu verschlucken.
   */
  function handleTileKeyDown(event: ReactKeyboardEvent<HTMLDivElement>) {
    const forward = event.key === 'ArrowRight' || event.key === 'ArrowDown'
    const backward = event.key === 'ArrowLeft' || event.key === 'ArrowUp'
    const home = event.key === 'Home'
    const end = event.key === 'End'
    if (!forward && !backward && !home && !end) return
    if (selectableTypes.length === 0) return
    event.preventDefault()
    const current = selectableTypes.indexOf(sourceType)
    const next = home
      ? selectableTypes[0]
      : end
        ? selectableTypes[selectableTypes.length - 1]
        : selectableTypes[
            (current + (forward ? 1 : selectableTypes.length - 1) + selectableTypes.length) %
              selectableTypes.length
          ]
    setSourceType(next)
    setError(null)
    event.currentTarget.querySelector<HTMLButtonElement>(`[data-source-type="${next}"]`)?.focus()
  }

  // Der Fokus folgt dem Schritt (WCAG 2.4.3): Nach „Weiter" steht er auf der Überschrift des neuen
  // Schritts, nicht auf dem Knopf, der gerade verschwunden ist.
  const stepHeadingRef = useRef<HTMLHeadingElement>(null)
  const firstRender = useRef(true)
  useEffect(() => {
    if (firstRender.current) {
      firstRender.current = false
      return
    }
    stepHeadingRef.current?.focus()
  }, [activeStep])

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
    if (currentStep === STEP_SOURCE) {
      const validationError = validateLibrarySourceFields(sourceType, {
        ...generic,
        confluence,
        s3,
      })
      if (validationError) {
        setError(validationError)
        return
      }
      const scheduleError = validateScheduleValues(schedule, confluenceRhythm)
      if (scheduleError) {
        setError(scheduleError)
        return
      }
      // Der Name kommt, wo die Quelle ihn hergibt, aus ihr - solange niemand selbst getippt hat.
      if (!nameTouched && name.trim() === '') {
        setName(nameFromSource(sourceType, { generic, confluence, s3 }))
      }
    }
    if (currentStep === STEP_NAME && name.trim() === '') {
      setError('Bitte einen Namen angeben')
      return
    }
    if (currentStep === STEP_SHARING && ownerType === 'GROUP' && !selectedGroup) {
      setError('Bitte eine Gruppe auswählen')
      return
    }
    setError(null)
    setActiveStep((s) => s + 1)
  }

  const handleCreate = async () => {
    if (ownerType === 'GROUP' && !selectedGroup) {
      setError('Bitte eine Gruppe auswählen')
      return
    }
    const scheduleError = validateScheduleValues(schedule, confluenceRhythm)
    if (scheduleError) {
      setError(scheduleError)
      return
    }
    setSubmitting(true)
    setError(null)
    try {
      const libraryId = await createNewLibrary({
        name: name.trim(),
        description: description.trim() || undefined,
        ownerType,
        ownerId: ownerType === 'GROUP' ? (selectedGroup?.id ?? undefined) : undefined,
        listed,
        sourceType,
        ...deriveLibrarySourceConfigPayload(sourceType, {
          ...generic,
          confluence,
          s3,
        }),
        // #1942: Anlage und Zeitplan werden atomar gesetzt; eine Upload-Bibliothek bekommt gar
        // keinen (das Backend wiese alles außer DISABLED mit 400 ab).
        ...(sourceType !== 'UPLOAD'
          ? scheduleUpdateFrom(schedule, confluenceRhythm, 'create')
          : {}),
      })
      await applyPendingGrantsAfterCreation('KNOWLEDGE_LIBRARY', libraryId, pendingGrants)
      if (sourceType !== 'UPLOAD' && startFirstRun) {
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

  const firstRunHint =
    configKind === 'confluence'
      ? 'Der erste Lauf ist ein Vollabgleich über alle ausgewählten Spaces; sein Stand bleibt auf der Detailseite sichtbar.'
      : configKind === 's3'
        ? 'Der erste Lauf ist ein Vollabgleich über alle Geltungsbereiche; sein Stand bleibt auf der Detailseite sichtbar.'
        : 'Der erste Lauf liest die Quelle vollständig ein; sein Stand bleibt auf der Detailseite sichtbar.'

  return (
    <Box sx={{ flexGrow: 1, overflowY: 'auto', p: { xs: 2.5, md: 5 } }}>
      <Box sx={{ maxWidth: 720 }}>
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mb: 0.5 }}>
          Neue Wissensbibliothek
        </Typography>
        <PageHeading title="Neue Wissensbibliothek" visuallyHidden />
        <Typography
          ref={stepHeadingRef}
          component="h2"
          tabIndex={-1}
          sx={{ fontSize: 26, fontWeight: 600, mb: 3 }}
        >
          {stepHeadings[currentStep] ?? currentStep}
        </Typography>
        <WizardStepBar steps={steps} active={activeStep} />

        {missingCapability && (
          <Alert severity="info" sx={{ mb: 2 }} id="library-create-capability-hint">
            {missingCapability}
          </Alert>
        )}

        {/* Ein Fehler eines Schritts wird angesagt, ohne den Fokus zu verschieben (WCAG 4.1.3). */}
        <Box role="alert" aria-live="polite">
          {error && (
            <Alert severity="error" sx={{ mb: 2 }}>
              {error}
            </Alert>
          )}
        </Box>

        {currentStep === STEP_ART && (
          <Box
            role="radiogroup"
            aria-label="Art des Wissens wählen"
            onKeyDown={handleTileKeyDown}
            sx={{
              display: 'grid',
              gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr' },
              gap: '14px',
            }}
          >
            {allDocumentSourceTypes.map((type) => {
              const selected = type === sourceType
              const missing = missingFor(type)
              return (
                <Box
                  key={type}
                  component="button"
                  type="button"
                  role="radio"
                  data-source-type={type}
                  aria-checked={selected}
                  aria-disabled={missing != null}
                  disabled={missing != null}
                  // Eine Radiogruppe hat genau einen Halt in der Tabulatorreihenfolge (WAI-ARIA
                  // „radio group", roving tabindex); zwischen den Kacheln führen die Pfeiltasten.
                  tabIndex={selected ? 0 : -1}
                  onClick={() => {
                    if (missing) return
                    setSourceType(type)
                    setError(null)
                  }}
                  sx={{
                    display: 'flex',
                    alignItems: 'flex-start',
                    gap: '12px',
                    textAlign: 'left',
                    font: 'inherit',
                    cursor: missing ? 'not-allowed' : 'pointer',
                    opacity: missing ? 0.6 : 1,
                    p: 2,
                    border: selected ? 2 : 1,
                    borderColor: selected ? 'primary.main' : 'divider',
                    borderRadius: '10px',
                    color: 'text.primary',
                    bgcolor: selected
                      ? (theme) =>
                          theme.palette.mode === 'dark'
                            ? alpha(theme.palette.primary.main, 0.16)
                            : blue[50]
                      : 'transparent',
                    '&:hover': { borderColor: selected ? 'primary.main' : 'text.disabled' },
                  }}
                >
                  <Box aria-hidden sx={{ display: 'flex', color: 'text.secondary', mt: '2px' }}>
                    <SourceTypeIcon sourceType={type} fontSize={22} />
                  </Box>
                  <Box>
                    <Typography sx={{ fontSize: 14.5, fontWeight: 600 }}>
                      {documentSourceTypeLabel(type)}
                    </Typography>
                    <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 0.25 }}>
                      {documentSourceTypeDescription(type)}
                    </Typography>
                    {missing && (
                      <Typography sx={{ fontSize: 12.5, color: 'warning.main', mt: 0.5 }}>
                        {missing}
                      </Typography>
                    )}
                  </Box>
                </Box>
              )
            })}
          </Box>
        )}

        {currentStep === STEP_SOURCE && (
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 3, maxWidth: 640 }}>
            {configKind === 'confluence' && (
              <ConfluenceSourceForm
                mode="create"
                idPrefix="library-create-confluence"
                values={confluence}
                onChange={(patch) => {
                  setConfluence((prev) => ({ ...prev, ...patch }))
                  setError(null)
                }}
              />
            )}

            {configKind === 's3' && (
              <S3SourceForm
                mode="create"
                idPrefix="library-create-s3"
                values={s3}
                onChange={(patch) => {
                  setS3((prev) => ({ ...prev, ...patch }))
                  setError(null)
                }}
              />
            )}

            {configKind === 'path' && (
              <PathSourceForm
                mode="create"
                idPrefix="library-create"
                values={generic}
                onChange={(patch) => setGeneric((prev) => ({ ...prev, ...patch }))}
              />
            )}

            {configKind === 'url' && (
              <UrlSourceForm
                mode="create"
                sourceType={sourceType}
                idPrefix="library-create"
                values={generic}
                onChange={(patch) => setGeneric((prev) => ({ ...prev, ...patch }))}
              />
            )}

            {(configKind === 'path' || configKind === 'url') && (
              <SourceConnectionTest sourceType={sourceType} values={generic} />
            )}

            {/* Zeitplan und Sofortstart gelten seit #1942 für jeden Konnektortyp, nicht mehr nur
                für Confluence und S3. */}
            <Box>
              <Typography component="h3" sx={{ fontSize: 16, fontWeight: 600, mb: 1.75 }}>
                Zeitplan
              </Typography>
              <LibraryScheduleForm
                idPrefix="library-create-schedule"
                values={schedule}
                onChange={(patch) => {
                  setSchedule((prev) => ({ ...prev, ...patch }))
                  setError(null)
                }}
                confluence={confluenceRhythm}
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
                  ? firstRunHint
                  : 'Ohne Sofortstart beginnt die Indizierung erst über „Jetzt indizieren“ auf der Detailseite oder über den Zeitplan.'}
              </Typography>
            </Box>
          </Box>
        )}

        {currentStep === STEP_NAME && (
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2.5, maxWidth: 640 }}>
            {configKind === 'none' && (
              <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
                Dokumente laden Sie nach dem Anlegen auf der Detailseite hoch — einzeln oder
                gebündelt.
              </Typography>
            )}
            <AssetNameFields
              idPrefix="library-create"
              name={name}
              onNameChange={(next) => {
                setNameTouched(true)
                setName(next)
                setError(null)
              }}
              description={description}
              onDescriptionChange={setDescription}
              namePlaceholder="z. B. Rechtsquellen Soziales"
            />
          </Box>
        )}

        {currentStep === STEP_SHARING && (
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2.5, maxWidth: 640 }}>
            <AssetOwnerFields
              idPrefix="library-create"
              assetType="KNOWLEDGE_LIBRARY"
              ownerType={ownerType}
              onOwnerTypeChange={(next) => {
                setOwnerType(next)
                setError(null)
              }}
              myGroups={myGroups}
              selectedGroup={selectedGroup}
              onSelectedGroupChange={setSelectedGroup}
            />
            <AssetRightsFields
              idPrefix="library-create"
              listed={{ value: listed, onChange: setListed }}
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
          {currentStep === STEP_SHARING && pendingGrants.length > 0 && (
            <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>
              {pendingGrants.length === 1
                ? '1 Freigabe vorgemerkt'
                : `${pendingGrants.length} Freigaben vorgemerkt`}
            </Typography>
          )}
          {activeStep > 0 && (
            <Button
              variant="outlined"
              onClick={() => {
                setError(null)
                setActiveStep((s) => s - 1)
              }}
              disabled={submitting}
            >
              Zurück
            </Button>
          )}
          {activeStep < steps.length - 1 ? (
            <Button variant="contained" onClick={handleNext}>
              Weiter
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
