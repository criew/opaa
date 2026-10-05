import { useEffect, useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import FormControl from '@mui/material/FormControl'
import FormControlLabel from '@mui/material/FormControlLabel'
import FormHelperText from '@mui/material/FormHelperText'
import FormLabel from '@mui/material/FormLabel'
import Radio from '@mui/material/Radio'
import RadioGroup from '@mui/material/RadioGroup'
import Switch from '@mui/material/Switch'
import Typography from '@mui/material/Typography'
import { useNavigate } from 'react-router'
import PageHeading from '../components/a11y/PageHeading'
import ChoiceTileGroup from '../components/choice/ChoiceTileGroup'
import { CATALOG_ROUTE } from '../routes'
import LibraryScheduleForm from '../components/library/LibraryScheduleForm'
import SourceTypeIcon from '../components/library/sourceTypeIcon'
import { registeredSourceTypes, sourceRegistration } from '../components/library/sources/registry'
import type { SourceFormContext } from '../components/library/sources/types'
import ConnectionProfileSelect from '../components/library/ConnectionProfileSelect'
import ConnectionProfileRequestAction from '../components/library/ConnectionProfileRequestAction'
import {
  OWN_ADDRESS,
  effectiveConnection,
  privateProfileOf,
  profileOfChoice,
  selectableConnections,
} from '../components/library/connectionChoice'
import { PRIVATE_LIBRARY_NOTE } from '../components/library/privateLibrary'
import SourceConsentPanel from '../components/library/SourceConsentPanel'
import {
  connectsSource,
  forgetConsentIntent,
  readConsentIntent,
  rememberConsentIntent,
  withoutSecrets,
  type SourceConsentIntent,
} from '../components/library/sourceConsent'
import { startSourceAuthorization } from '../services/connectedAccountApi'
import { leaveFor } from '../services/leaveApp'
import { apiErrorCode } from '../services/apiErrorDetails'
import {
  connectionFields,
  ownAddressAllowed,
  payloadUnder,
  sourceConnectionOf,
  switchConnection,
  withConnection,
  withoutFixed,
} from '../components/library/sources/sourceConnection'
import { useConnectionProfileOptions } from '../hooks/useConnectionProfileOptions'
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
import { useSourceTypes } from '../hooks/useSourceTypes'
import AssetNameFields from '../components/assets/AssetNameFields'
import AssetOwnerFields from '../components/assets/AssetOwnerFields'
import AssetRightsFields from '../components/assets/AssetRightsFields'
import {
  applyPendingGrantsAfterCreation,
  type PendingGrant,
} from '../components/assets/pendingGrants'
import { capabilityMissingMessage } from '../utils/labels'
import {
  documentSourceTypeLabel,
  documentSourceTypeDescription,
} from '../components/library/sources/sourceLabels'
import type {
  Capability,
  SourceTypeKey,
  GroupListResponse,
  AssetOwnerType,
  PendingSourceConnection,
  SourceTypeDescriptor,
} from '../types/api'

/**
 * Die Schritte des Assistenten (#1942): jeder trägt den Namen des Reiters bzw. des Kopfes, den er
 * in der Detailansicht bekommt. Eine Upload-Bibliothek hat keine Quelle und deshalb drei Schritte.
 */
const STEP_ART = 'Art des Wissens'
const STEP_SOURCE = 'Quelle'
const STEP_NAME = 'Name & Beschreibung'
const STEP_SHARING = 'Freigaben'

/** A private library has no sharing step: only its owner ever reads it. */
function stepsFor(acceptsUploads: boolean, privateLibrary: boolean): string[] {
  if (acceptsUploads) return [STEP_ART, STEP_NAME, STEP_SHARING]
  return privateLibrary
    ? [STEP_ART, STEP_SOURCE, STEP_NAME]
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

const CONSENT_REQUIRED = 'Bitte verbinden Sie zuerst die Quelle.'
const PENDING_CONNECTION_UNUSABLE =
  'Die Zustimmung beim Anbieter ist abgelaufen oder schon verwendet. Bitte verbinden Sie die Quelle erneut.'

type DraftIntent = Extract<SourceConsentIntent, { purpose: 'LIBRARY_NEW' }>

/** The draft the provider returned to; the wizard forgets it as soon as it has mounted. */
function restoredDraft(): DraftIntent | null {
  const intent = readConsentIntent()
  return intent?.purpose === 'LIBRARY_NEW' ? intent : null
}

/** The entered values of a restored draft under the key its form keeps them. */
function restoredSourceValues(intent: DraftIntent | null): Record<SourceTypeKey, unknown> {
  if (!intent) return {}
  const type = intent.draft.sourceType
  const configuration = sourceRegistration(type)?.configuration
  if (!configuration) return {}
  return {
    [configuration.valuesKey ?? type]: { ...configuration.empty, ...intent.draft.sourceValues },
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

  // A consent for the source leaves for the provider; the wizard comes back on „Quelle“.
  const [restored] = useState(restoredDraft)
  const draft = restored?.draft
  const [activeStep, setActiveStep] = useState(restored ? 1 : 0)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  // ADR-0036, Entscheidung 5: uploads and connectors are separate Anlegerechte, because a
  // connector library reaches server paths and stored credentials. A missing right is explained
  // rather than hidden; the backend refuses the same call regardless.
  const { isMissing } = useMyCapabilities()

  const [name, setName] = useState(draft?.name ?? '')
  const [nameTouched, setNameTouched] = useState(draft?.nameTouched ?? false)
  const [description, setDescription] = useState(draft?.description ?? '')
  const [ownerType, setOwnerType] = useState<AssetOwnerType>(draft?.ownerType ?? 'USER')
  const [selectedGroup, setSelectedGroup] = useState<GroupListResponse | null>(
    draft?.selectedGroup ?? null,
  )
  const myGroups = useMyGroups()

  const [chosenType, setSourceType] = useState<SourceTypeKey>(draft?.sourceType ?? 'UPLOAD')
  // The entered values of every source form, by type key - a form keeps its values when another
  // tile is chosen and chosen back.
  const [sourceValues, setSourceValues] = useState<Record<SourceTypeKey, unknown>>(() =>
    restoredSourceValues(restored),
  )
  // The chosen profile (or OWN_ADDRESS) by type key; what stands in effect derives from it.
  const [chosenConnections, setChosenConnections] = useState<Record<SourceTypeKey, string>>(
    draft?.chosenConnections ?? {},
  )
  const [schedule, setSchedule] = useState<LibraryScheduleValues>(
    () => draft?.schedule ?? scheduleValuesFrom(null),
  )
  // Opt-out, not opt-in: whoever just configured a source expects content - the first run starts
  // right after creation unless switched off. Since #1942 for every connector type, not only
  // Confluence and S3.
  const [startFirstRun, setStartFirstRun] = useState(draft?.startFirstRun ?? true)
  const [pendingGrants, setPendingGrants] = useState<PendingGrant[]>(draft?.pendingGrants ?? [])
  // The consent given at the provider, by the profile it was given on; it waits for the creation.
  const [pending, setPending] = useState<{
    profileId: string
    connection: PendingSourceConnection
  } | null>(() =>
    restored?.pending ? { profileId: restored.profileId, connection: restored.pending } : null,
  )
  const [serviceAccountConfirmed, setServiceAccountConfirmed] = useState(false)
  const [confirmationMissing, setConfirmationMissing] = useState(false)
  const [connectingSource, setConnectingSource] = useState(false)
  const [responsibleIsGroup, setResponsibleIsGroup] = useState(draft?.responsibleIsGroup ?? false)

  // Die Kacheln sind die Quellarten, für die das Backend einen Konnektor hat (ADR-0038), in der
  // Reihenfolge der Eingabemasken; eine Art ohne Maske steht am Ende und ist nicht wählbar.
  const { sourceTypes, error: sourceTypesError, loaded: sourceTypesLoaded } = useSourceTypes()
  const offeredTypes: SourceTypeKey[] = [
    ...registeredSourceTypes.filter((type) => sourceTypes.some((d) => d.type === type)),
    ...sourceTypes.map((d) => d.type).filter((type) => sourceRegistration(type) === undefined),
  ]
  const displayNameOf = (type: SourceTypeKey) =>
    sourceRegistration(type) !== undefined
      ? documentSourceTypeLabel(type)
      : (sourceTypes.find((d) => d.type === type)?.displayName ?? type)
  const acceptsUploads = (type: SourceTypeKey) =>
    sourceTypes.find((d) => d.type === type)?.uploads ?? type === 'UPLOAD'
  const capabilityFor = (type: SourceTypeKey): Capability =>
    acceptsUploads(type) ? 'CREATE_LIBRARY' : 'CREATE_CONNECTOR_LIBRARY'

  /** Die Begründung, warum diese Art hier nicht zu wählen ist - oder `null`, wenn sie es ist. */
  function missingFor(type: SourceTypeKey): string | null {
    const registration = sourceRegistration(type)
    if (registration === undefined || (!acceptsUploads(type) && !registration.configuration)) {
      return 'Für diese Quellart gibt es in dieser Oberfläche keine Eingabemaske.'
    }
    const capability = capabilityFor(type)
    if (isMissing(capability)) return capabilityMissingMessage(capability)
    return releaseMissingFor(type)
  }

  /**
   * Die Konnektor-Freigabe gilt je Quellart und je Zugang (ADR-0036, Nachtrag vom 03.10.2026): eine
   * Art, die die Person weder mit eigener Adresse noch über einen Zugang anlegen darf oder die
   * gesperrt ist, bleibt mit dem Hinweis des Backends sichtbar. Welcher Weg offen ist, wählt der
   * Schritt „Quelle“.
   */
  function releaseMissingFor(type: SourceTypeKey): string | null {
    const descriptor = sourceTypes.find((d) => d.type === type)
    if (descriptor === undefined || descriptor.uploads) return null
    if (descriptor.creatableWithOwnAddress) return null
    if (descriptor.creatable && descriptor.profileSupport !== 'FORBIDDEN') return null
    return (
      descriptor.creationNotice ??
      `Die Quellart „${descriptor.displayName}“ ist für Sie nicht freigegeben. Freigaben erteilt die Systemverwaltung.`
    )
  }

  const selectableTypes = offeredTypes.filter((type) => missingFor(type) === null)

  /**
   * Die Arten, die nur deshalb gesperrt sind, weil der Person kein nutzbarer Zugang zur Verfügung
   * steht: Für sie bietet schon dieser Schritt „Zugang vorschlagen“ an, sonst wäre der Wunsch
   * gerade dort unerreichbar, wo er gebraucht wird. Voraussetzung ist das Konnektor-Anlegerecht in
   * irgendeinem Geltungsbereich, wie für das Backend.
   */
  const suggestableTypes = isMissing('CREATE_CONNECTOR_LIBRARY')
    ? []
    : offeredTypes
        .map((type) => sourceTypes.find((d) => d.type === type))
        .filter(
          (d): d is SourceTypeDescriptor =>
            d !== undefined &&
            !d.uploads &&
            !d.locked &&
            !d.creatable &&
            d.profileSupport !== 'FORBIDDEN' &&
            Boolean(sourceRegistration(d.type)?.configuration),
        )

  /**
   * Die tatsächlich gewählte Art. Die Anlegerechte kommen erst nach dem ersten Rendern an; was
   * dann gesperrt ist, darf nicht ausgewählt stehen bleiben, sonst führte „Weiter" in einen Pfad,
   * der nie anlegen kann. Die Wahl rückt auf die erste erlaubte Kachel - abgeleitet, nicht in
   * einem Effekt nachgezogen. Ist gar keine erlaubt, bleibt sie stehen, und der Hinweis über der
   * Schrittleiste erklärt, warum am Ende nichts angelegt wird.
   */
  const sourceType =
    (missingFor(chosenType) !== null || !selectableTypes.includes(chosenType)) &&
    selectableTypes.length > 0
      ? selectableTypes[0]
      : chosenType

  const uploadLibrary = acceptsUploads(sourceType)
  const configuration = uploadLibrary
    ? null
    : (sourceRegistration(sourceType)?.configuration ?? null)
  const valuesKey = configuration?.valuesKey ?? sourceType

  // Der Zugang ist eine Wahl des Assistenten, nicht der Quellformulare: Sie steht über dem
  // Formular und gibt ihm Adresse, Anmeldeart und Vorgaben mit.
  const descriptor = sourceTypes.find((d) => d.type === sourceType)
  const admitsProfiles =
    configuration !== null && descriptor !== undefined && descriptor.profileSupport !== 'FORBIDDEN'
  const profileOptions = useConnectionProfileOptions(admitsProfiles ? sourceType : null)
  // Freiwilligkeit: the private way exists only on a profile the person already connected an
  // account on (ConnectionProfileOption.ownAccount); without one nothing hints at it.
  const selectable =
    admitsProfiles && descriptor
      ? selectableConnections(descriptor, profileOptions.options, true)
      : []
  const connectionChoice =
    admitsProfiles && descriptor
      ? effectiveConnection(chosenConnections[sourceType] ?? null, selectable)
      : OWN_ADDRESS
  const privateLibrary = privateProfileOf(connectionChoice) !== null
  const chosenProfile = profileOptions.options.find(
    (option) => option.id === profileOfChoice(connectionChoice),
  )
  const connection = chosenProfile ? sourceConnectionOf(chosenProfile) : undefined
  const consentProfile = connectsSource(chosenProfile, privateLibrary) ? chosenProfile : undefined
  const pendingConnection =
    consentProfile && pending?.profileId === consentProfile.id ? pending.connection : null
  const awaitsConsent = consentProfile !== undefined && pendingConnection === null
  const groupResponsible =
    consentProfile !== undefined && ownerType === 'GROUP' && selectedGroup !== null
  const steps = stepsFor(uploadLibrary, privateLibrary)
  const currentStep = steps[Math.min(activeStep, steps.length - 1)]
  const showConnectionSelect =
    admitsProfiles &&
    descriptor !== undefined &&
    !(
      profileOptions.loaded &&
      profileOptions.error === null &&
      profileOptions.options.length === 0 &&
      ownAddressAllowed(descriptor)
    )

  const values: unknown = withConnection(
    sourceValues[valuesKey] ?? configuration?.empty,
    connection,
  )
  const formContext: SourceFormContext = {
    mode: 'create',
    sourceType,
    idPrefix: 'library-create',
    credentialsStored: false,
    connection,
    privateLibrary,
    pendingConnectionId: pendingConnection?.id,
  }

  /** A new choice of way; address and fixed fields of the previous one do not carry over. */
  function chooseConnection(next: string) {
    if (next === connectionChoice) return
    setChosenConnections((prev) => ({ ...prev, [sourceType]: next }))
    const nextOption = profileOptions.options.find((option) => option.id === profileOfChoice(next))
    // private and shared on the same profile share its address and fixed fields
    if (configuration && profileOfChoice(next) !== profileOfChoice(connectionChoice)) {
      setSourceValues((prev) => ({
        ...prev,
        [valuesKey]: switchConnection(
          values,
          configuration.empty,
          connection,
          nextOption ? sourceConnectionOf(nextOption) : null,
          configuration.addressDerived,
        ),
      }))
    }
    setError(null)
  }
  const confluenceRhythm = configuration?.fullSyncRhythm ? NEW_CONFLUENCE_RHYTHM : undefined
  // Without the list of source types nothing may be chosen - not even the upload library a stale
  // default would otherwise let through.
  const typesUnavailable = !sourceTypesLoaded || sourceTypesError != null

  const requiredCapability: Capability = capabilityFor(sourceType)
  const missingCapability = isMissing(requiredCapability)
    ? capabilityMissingMessage(requiredCapability)
    : releaseMissingFor(sourceType)

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

  // Back from the provider: the draft is consumed, and the focus lands on the step it left.
  useEffect(() => {
    if (!restored) return
    forgetConsentIntent()
    stepHeadingRef.current?.focus()
  }, [restored])

  /** The configuration whose form keeps its values under `key`. */
  const configurationForValues = (key: string) =>
    registeredSourceTypes
      .map((type) => ({ type, registered: sourceRegistration(type)?.configuration }))
      .find(({ type, registered }) => registered && (registered.valuesKey ?? type) === key)
      ?.registered

  const isDirty =
    name.trim() !== '' ||
    description.trim() !== '' ||
    Object.entries(sourceValues).some(([key, entered]) =>
      Boolean(configurationForValues(key)?.isDirty(entered)),
    ) ||
    (!privateLibrary && pendingGrants.length > 0) ||
    pending !== null

  const handleCancel = async () => {
    if (isDirty) {
      const confirmed = await confirmAction({
        question: 'Eingaben verwerfen und den Assistenten verlassen?',
        confirmLabel: 'Verwerfen',
        tone: 'caution',
      })
      if (!confirmed) return
    }
    forgetConsentIntent()
    navigate(CATALOG_ROUTE)
  }

  /** Keeps the draft for the return and leaves for the provider's consent in this tab. */
  async function handleConnectSource() {
    if (!consentProfile || connectingSource) return
    if (!serviceAccountConfirmed) {
      setConfirmationMissing(true)
      return
    }
    setConnectingSource(true)
    setError(null)
    rememberConsentIntent({
      purpose: 'LIBRARY_NEW',
      profileId: consentProfile.id,
      draft: {
        sourceType,
        chosenConnections,
        sourceValues: withoutSecrets(sourceValues[valuesKey] ?? configuration?.empty),
        schedule,
        startFirstRun,
        name,
        nameTouched,
        description,
        ownerType,
        selectedGroup,
        pendingGrants,
        responsibleIsGroup,
      },
    })
    try {
      const started = await startSourceAuthorization({
        profileId: consentProfile.id,
        purpose: 'LIBRARY_NEW',
        serviceAccountConfirmed: true,
      })
      leaveFor(started.authorizationUrl)
    } catch (err) {
      forgetConsentIntent()
      setError(
        err instanceof Error && err.message
          ? err.message
          : 'Die Anmeldung beim Anbieter ließ sich nicht starten.',
      )
      setConnectingSource(false)
    }
  }

  const handleNext = () => {
    if (currentStep === STEP_ART && typesUnavailable) {
      setError(sourceTypesError ?? 'Die Quellarten werden noch geladen')
      return
    }
    if (currentStep === STEP_SOURCE && configuration) {
      if (connectionChoice === null) {
        setError(
          !profileOptions.loaded
            ? 'Die Zugänge werden noch geladen'
            : selectable.length > 0
              ? 'Bitte wählen Sie einen Zugang.'
              : 'Für diese Quellart steht Ihnen kein Zugang zur Verfügung.',
        )
        return
      }
      if (awaitsConsent) {
        setError(CONSENT_REQUIRED)
        return
      }
      const validationError = configuration.validate(values, formContext)
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
        setName(configuration.nameFromSource(values))
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
    if (!privateLibrary && ownerType === 'GROUP' && !selectedGroup) {
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
      const source = configuration
        ? payloadUnder(configuration.toPayload(values), connectionFields(formContext))
        : { sourceInsecureSsl: false }
      // #1942: Anlage und Zeitplan werden atomar gesetzt; eine Upload-Bibliothek bekommt gar
      // keinen (das Backend wiese alles außer DISABLED mit 400 ab).
      const scheduled = !uploadLibrary
        ? scheduleUpdateFrom(schedule, confluenceRhythm, 'create')
        : undefined
      const sourceSettings =
        source.sourceSettings || scheduled?.sourceSettings
          ? { ...source.sourceSettings, ...scheduled?.sourceSettings }
          : undefined
      // A private library belongs to the caller alone and carries no grant but hers.
      const owner = privateLibrary ? 'USER' : ownerType
      const libraryId = await createNewLibrary({
        name: name.trim(),
        description: description.trim() || undefined,
        ownerType: owner,
        ownerId: owner === 'GROUP' ? (selectedGroup?.id ?? undefined) : undefined,
        sourceType,
        ...(connection ? { connectionProfileId: connection.profileId } : {}),
        ...(privateLibrary ? { privateLibrary: true } : {}),
        ...(pendingConnection ? { pendingConnectionId: pendingConnection.id } : {}),
        ...(pendingConnection && groupResponsible && responsibleIsGroup && selectedGroup
          ? { sourceConnectionResponsible: { type: 'GROUP' as const, id: selectedGroup.id } }
          : {}),
        ...source,
        sourceSettings,
        ...(scheduled ? { schedule: scheduled.schedule } : {}),
      })
      if (!privateLibrary) {
        await applyPendingGrantsAfterCreation('KNOWLEDGE_LIBRARY', libraryId, pendingGrants)
      }
      if (!uploadLibrary && startFirstRun) {
        // Awaited so the run is already in the indexing store when the detail page mounts and its
        // progress strip picks it up. triggerIndexing never throws - a failure surfaces through
        // the global indexing snackbar, and the detail page still offers "Jetzt indizieren".
        await triggerIndexing(libraryId, sourceType)
      }
      forgetConsentIntent()
      navigate(`/libraries/${libraryId}`)
    } catch (err) {
      if (apiErrorCode(err) === 'PENDING_CONNECTION_UNUSABLE') {
        // expired or used up: back to „Quelle“, where the source is connected again
        setPending(null)
        setServiceAccountConfirmed(false)
        setActiveStep(steps.indexOf(STEP_SOURCE))
        setError(PENDING_CONNECTION_UNUSABLE)
      } else {
        setError(err instanceof Error ? err.message : 'Bibliothek konnte nicht erstellt werden')
      }
      setSubmitting(false)
    }
  }

  const firstRunHint =
    configuration?.firstRunHint ??
    'Der erste Lauf liest die Quelle vollständig ein; sein Stand bleibt auf der Detailseite sichtbar.'
  const SourceForm = configuration?.Form

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

        {currentStep === STEP_ART && !sourceTypesLoaded && (
          <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
            Quellarten werden geladen …
          </Typography>
        )}

        {currentStep === STEP_ART && sourceTypesError && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {sourceTypesError}
          </Alert>
        )}

        {currentStep === STEP_ART && sourceTypesLoaded && !sourceTypesError && (
          <ChoiceTileGroup<SourceTypeKey>
            aria-label="Art des Wissens wählen"
            value={sourceType}
            onChange={(next) => {
              setSourceType(next)
              setError(null)
            }}
            tiles={offeredTypes.map((type) => ({
              value: type,
              label: displayNameOf(type),
              description: documentSourceTypeDescription(type),
              icon: <SourceTypeIcon sourceType={type} fontSize={22} />,
              disabledReason: missingFor(type),
            }))}
          />
        )}

        {currentStep === STEP_ART &&
          sourceTypesLoaded &&
          !sourceTypesError &&
          suggestableTypes.map((suggestable) => (
            <ConnectionProfileRequestAction
              key={suggestable.type}
              descriptor={suggestable}
              idPrefix={`library-create-${suggestable.type}`}
              prompt={`Für „${suggestable.displayName}“ steht Ihnen noch kein nutzbarer Zugang zur Verfügung.`}
            />
          ))}

        {currentStep === STEP_SOURCE && (
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 3, maxWidth: 640 }}>
            {showConnectionSelect && descriptor && (
              <ConnectionProfileSelect
                descriptor={descriptor}
                state={profileOptions}
                value={connectionChoice}
                onChange={chooseConnection}
                offerOwnAddress
                offerRequest
                offerPrivate
                idPrefix="library-create"
              />
            )}
            {admitsProfiles &&
              descriptor &&
              !showConnectionSelect &&
              profileOptions.loaded &&
              !isMissing('CREATE_CONNECTOR_LIBRARY') && (
                <ConnectionProfileRequestAction descriptor={descriptor} idPrefix="library-create" />
              )}
            {consentProfile && (
              <SourceConsentPanel
                profileName={consentProfile.name}
                pending={pendingConnection}
                confirmed={serviceAccountConfirmed}
                onConfirmedChange={(checked) => {
                  setServiceAccountConfirmed(checked)
                  if (checked) setConfirmationMissing(false)
                }}
                confirmationMissing={confirmationMissing}
                busy={connectingSource}
                onConnect={() => void handleConnectSource()}
              />
            )}
            {SourceForm && connectionChoice !== null && !awaitsConsent && (
              <SourceForm
                // a new profile starts the form afresh, so no probe result outlives its profile
                key={connectionChoice}
                values={values}
                context={formContext}
                onChange={(patch: object) => {
                  setSourceValues((prev) => ({
                    ...prev,
                    [valuesKey]: { ...(values as object), ...withoutFixed(patch, connection) },
                  }))
                  setError(null)
                }}
              />
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
            {uploadLibrary && (
              <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
                Dokumente laden Sie nach dem Anlegen auf der Detailseite hoch — einzeln oder
                gebündelt.
              </Typography>
            )}
            {privateLibrary && (
              <Alert severity="info" data-testid="library-create-private-note">
                {PRIVATE_LIBRARY_NOTE} Sie läuft über Ihr verbundenes Konto auf dem Zugang „
                {connection?.name}“ und lässt sich weder freigeben noch übertragen.
              </Alert>
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
            {consentProfile &&
              (groupResponsible ? (
                <FormControl>
                  <FormLabel id="library-create-responsible-label">
                    Verantwortlich für die Verbindung der Quelle
                  </FormLabel>
                  <RadioGroup
                    aria-labelledby="library-create-responsible-label"
                    aria-describedby="library-create-responsible-hint"
                    value={responsibleIsGroup ? 'GROUP' : 'USER'}
                    onChange={(event) => setResponsibleIsGroup(event.target.value === 'GROUP')}
                  >
                    <FormControlLabel value="USER" control={<Radio />} label="Ich" />
                    <FormControlLabel
                      value="GROUP"
                      control={<Radio />}
                      label={`Gruppe „${selectedGroup?.name}“`}
                    />
                  </RadioGroup>
                  <FormHelperText id="library-create-responsible-hint">
                    Erhält die Warnung, bevor die Zustimmung beim Anbieter endet, und die Nachricht,
                    wenn sie endet, und kann die Quelle neu verbinden.
                  </FormHelperText>
                </FormControl>
              ) : (
                <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
                  Für die Verbindung der Quelle sind Sie verantwortlich: Sie erhalten die Warnung,
                  bevor die Zustimmung beim Anbieter endet, und die Nachricht, wenn sie endet, und
                  können die Quelle neu verbinden.
                </Typography>
              ))}
            <AssetRightsFields
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
            <Button
              variant="contained"
              onClick={handleNext}
              disabled={currentStep === STEP_ART && typesUnavailable}
            >
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
