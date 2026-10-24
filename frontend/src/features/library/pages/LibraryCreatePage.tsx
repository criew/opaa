import { useEffect, useRef, useState } from 'react'
import { flushSync } from 'react-dom'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import { useNavigate } from 'react-router'
import PageHeading from '@/shared/ui/a11y/PageHeading'
import ChoiceTileGroup from '@/shared/ui/choice/ChoiceTileGroup'
import { CATALOG_ROUTE } from '@/shared/routes'
import { sourceTypeIcon as SourceTypeIcon } from '@/features/connection'
import { registeredSourceTypes, sourceRegistration } from '../components/sources/registry'
import type { SourceFormContext } from '../components/sources/types'
import { allSourceIssues, sourceTestRequest } from '../components/sources/sourceFrame'
import { fieldDomId } from '../components/sources/sourceIssueContext'
import { useConnectionCheck } from '../components/sourceKit/useConnectionCheck'
import ErrorSummary, { type SummaryIssue } from '../components/sourceKit/ErrorSummary'
import CreateReviewStep, { type ReviewSection } from '../components/CreateReviewStep'
import { scheduleFrequencyLabel, scheduleWeekdayLabel } from '../components/scheduleLabels'
import { notify } from '@/shared/stores/notificationStore'
import type { SourceIssue } from '../utils/sourceIssue'
import {
  OWN_ADDRESS,
  effectiveConnection,
  privateProfileOf,
  profileOfChoice,
  selectableConnections,
} from '../components/connectionChoice'
import { PRIVATE_LIBRARY_NOTE } from '../components/privateLibrary'
import { connectsSource, rememberConsentIntent, draftValues } from '../components/sourceConsent'
import { forgetConsentIntent } from '@/features/connection'
import { startSourceAuthorization } from '@/features/connection'
import { leaveFor } from '@/shared/api/leaveApp'
import { apiErrorCode } from '@/shared/api/apiErrorDetails'
import {
  connectionFields,
  ownAddressAllowed,
  payloadUnder,
  sourceConnectionOf,
  switchConnection,
  withConnection,
  withoutFixed,
} from '../components/sources/sourceConnection'
import { useConnectionProfileOptions } from '@/features/connection'
import {
  scheduleUpdateFrom,
  scheduleValuesFrom,
  validateScheduleValues,
  type ConfluenceFullSyncRhythm,
  type LibraryScheduleValues,
} from '../utils/librarySchedule'
import WizardStepBar from '@/shared/ui/wizard/WizardStepBar'
import { confirmAction } from '@/shared/stores/confirmStore'
import { useLibraryStore } from '../stores/libraryStore'
import { useIndexingStore } from '../stores/indexingStore'
import { useMyCapabilities } from '@/shared/session/useMyCapabilities'
import { useMyGroups } from '@/features/principal'
import { AssetNameFields } from '@/features/asset'
import { applyPendingGrantsAfterCreation, type PendingGrant } from '@/features/asset'
import { capabilityMissingMessage } from '@/shared/utils/labels'
import { documentSourceTypeDescription } from '../components/sources/sourceLabels'
import type {
  Capability,
  SourceTypeKey,
  GroupListResponse,
  AssetOwnerType,
  PendingSourceConnection,
} from '@/shared/api/types'
import {
  CONSENT_REQUIRED,
  NOTHING_CREATABLE,
  PENDING_CONNECTION_UNUSABLE,
  STEP_NAME,
  STEP_REVIEW,
  STEP_SHARING,
  STEP_SOURCE_SETUP,
  STEP_SOURCE_TYPE,
  restoredDraft,
  restoredSourceValues,
  stepHeadings,
  stepsFor,
} from '../components/libraryCreateWizard'
import { useSourceTypeChoice } from '../components/useSourceTypeChoice'
import CreateSourceSetupStep from '../components/CreateSourceSetupStep'
import CreateSharingStep from '../components/CreateSharingStep'
import LibraryCreateFooter from '../components/LibraryCreateFooter'

/**
 * The creation wizard for knowledge libraries. Every step
 * corresponds to a tab of the detail page and uses its forms - source forms,
 * connection test, schedule and the release building blocks are shared components, not fields
 * of this page.
 */
export default function LibraryCreatePage() {
  const navigate = useNavigate()
  const createNewLibrary = useLibraryStore((s) => s.createNewLibrary)
  const triggerIndexing = useIndexingStore((s) => s.triggerIndexing)

  // A consent for the source leaves for the provider; the wizard comes back on „Quelle einrichten“.
  const [restored] = useState(restoredDraft)
  const draft = restored?.draft
  const [activeStep, setActiveStep] = useState(restored ? 1 : 0)
  const [error, setError] = useState<string | null>(null)
  // the issues of the source form, each shown at its field and gathered in the error summary
  const [sourceIssues, setSourceIssues] = useState<SourceIssue[]>([])
  // issues of the step that belong to no source field (name, group, connection test)
  const [otherIssues, setOtherIssues] = useState<SummaryIssue[]>([])
  const [nameError, setNameError] = useState<string | null>(null)
  // after „Ändern“ on the review, the step's primary button leads straight back to it
  const [returnToReview, setReturnToReview] = useState(false)
  const summaryRef = useRef<HTMLDivElement>(null)
  const sourceFormRef = useRef<HTMLDivElement>(null)
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
  // right after creation unless switched off, for every connector type.
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

  const {
    sourceTypes,
    sourceTypesError,
    sourceTypesLoaded,
    reloadSourceTypes,
    displayNameOf,
    acceptsUploads,
    capabilityFor,
    missingFor,
    releaseMissingFor,
    shownTypes,
    nothingUsable,
    sourceType,
  } = useSourceTypeChoice(chosenType, isMissing)

  const uploadLibrary = acceptsUploads(sourceType)
  const configuration = uploadLibrary
    ? null
    : (sourceRegistration(sourceType)?.configuration ?? null)
  const valuesKey = configuration?.valuesKey ?? sourceType

  // The connection profile is a choice of the wizard, not of the source forms: it stands above the
  // form and hands it address, authentication type and defaults.
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
    setSourceIssues([])
  }
  // a new library has no rhythm of its own yet; empty means the instance's, named by the type
  const confluenceRhythm: ConfluenceFullSyncRhythm | undefined = configuration?.fullSyncRhythm
    ? { intervalDays: null, defaultDays: descriptor?.fullSyncIntervalDefaultDays ?? null }
    : undefined
  // Without the list of source types nothing may be chosen - not even the upload library a stale
  // default would otherwise let through.
  const typesUnavailable = !sourceTypesLoaded || sourceTypesError != null

  const requiredCapability: Capability = capabilityFor(sourceType)
  const missingCapability = nothingUsable
    ? NOTHING_CREATABLE
    : isMissing(requiredCapability)
      ? capabilityMissingMessage(requiredCapability)
      : releaseMissingFor(sourceType)

  // Focus follows the step (WCAG 2.4.3): after „Weiter" it is on the heading of the new
  // step, not on the button that has just disappeared.
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
        sourceValues: draftValues(sourceValues[valuesKey], configuration?.draftFields ?? []),
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

  const formReady = configuration !== null && connectionChoice !== null && !awaitsConsent
  const check = useConnectionCheck(
    formReady && configuration ? sourceTestRequest(configuration, values, formContext) : null,
  )
  const CHECK_ID = 'library-create-check'

  /** Shows the issues at their fields and in the summary, which takes the focus (Prüfliste #29). */
  function showIssues(source: SourceIssue[], other: SummaryIssue[]) {
    flushSync(() => {
      setSourceIssues(source)
      setOtherIssues(other)
    })
    summaryRef.current?.focus()
  }

  function clearIssues() {
    setError(null)
    setSourceIssues([])
    setOtherIssues([])
    setNameError(null)
  }

  /** Applies what a passed connection test taught the source (the Confluence edition). */
  function applyTested(details: Record<string, unknown> | null | undefined) {
    const patch = configuration?.onTested?.(details, values)
    if (patch) changeSource(patch)
  }

  function changeSource(patch: object) {
    setSourceValues((prev) => ({
      ...prev,
      [valuesKey]: {
        ...(withConnection(prev[valuesKey] ?? configuration?.empty, connection) as object),
        ...withoutFixed(patch, connection),
      },
    }))
    setError(null)
  }

  // An issue at a field goes as soon as the field is valid; none appears before the next submit
  // (Prüfliste #29: validate on submit, re-check while an error shows).
  const currentIssues =
    configuration && sourceIssues.length > 0
      ? allSourceIssues(configuration, values, formContext)
      : []
  const shownSourceIssues = sourceIssues
    .map((shown) => currentIssues.find((issue) => issue.field === shown.field && shown.field))
    .filter((issue): issue is SourceIssue => issue !== undefined)

  const goForward = () => {
    clearIssues()
    if (returnToReview) {
      setActiveStep(steps.indexOf(STEP_REVIEW))
      setReturnToReview(false)
    } else {
      setActiveStep((s) => s + 1)
    }
  }

  const handleNext = async () => {
    if (check.running) return
    if (currentStep === STEP_SOURCE_TYPE && typesUnavailable) {
      setError(sourceTypesError ?? 'Die Quellarten werden noch geladen')
      return
    }
    // the hint above the step bar already says why nothing can be created
    if (currentStep === STEP_SOURCE_TYPE && nothingUsable) return
    if (currentStep === STEP_SOURCE_SETUP && configuration) {
      if (connectionChoice === null) {
        showIssues(
          [],
          [
            {
              message: !profileOptions.loaded
                ? 'Die Zugänge werden noch geladen.'
                : selectable.length > 0
                  ? 'Bitte einen Zugang wählen.'
                  : 'Für diese Quellart steht Ihnen kein Zugang zur Verfügung.',
            },
          ],
        )
        return
      }
      if (awaitsConsent) {
        showIssues([], [{ message: CONSENT_REQUIRED }])
        return
      }
      const found = allSourceIssues(configuration, values, formContext)
      const scheduleError = validateScheduleValues(schedule, confluenceRhythm)
      if (found.length > 0 || scheduleError) {
        showIssues(found, scheduleError ? [{ message: scheduleError }] : [])
        return
      }
      // the connection is tested before „Weiter“ unless it passed or the person chose to go on
      if (check.available && !check.passed && !check.waived) {
        const outcome = await check.run()
        if (!outcome.ok) {
          showIssues(
            [],
            [
              {
                message:
                  'Der Verbindungstest ist fehlgeschlagen. Angaben prüfen oder „Trotzdem fortfahren“ wählen.',
                targetId: CHECK_ID,
              },
            ],
          )
          return
        }
        applyTested(outcome.result?.details)
      }
      // Where the source provides it, the name comes from it - as long as nobody has typed one.
      if (!nameTouched && name.trim() === '') {
        setName(configuration.nameFromSource(values))
      }
    }
    if (currentStep === STEP_NAME && name.trim() === '') {
      setNameError('Bitte einen Namen eingeben.')
      showIssues([], [{ message: 'Bitte einen Namen eingeben.', targetId: 'library-create-name' }])
      return
    }
    if (currentStep === STEP_SHARING && ownerType === 'GROUP' && !selectedGroup) {
      showIssues([], [{ message: 'Bitte eine Gruppe auswählen.' }])
      return
    }
    goForward()
  }

  /** „Ändern“ on the review: opens the step and comes back after it. */
  const changeStep = (step: string) => {
    clearIssues()
    setReturnToReview(true)
    setActiveStep(steps.indexOf(step))
  }

  const handleCreate = async () => {
    if (submitting) return
    if (missingCapability) {
      showIssues([], [{ message: missingCapability }])
      return
    }
    if (name.trim() === '') {
      showIssues([], [{ message: 'Bitte einen Namen eingeben.' }])
      return
    }
    if (!privateLibrary && ownerType === 'GROUP' && !selectedGroup) {
      showIssues([], [{ message: 'Bitte eine Gruppe auswählen.' }])
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
        : {}
      // Creation and schedule are set atomically; an upload library gets none at all
      // (the backend would reject anything but DISABLED with 400).
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
      notify(`Bibliothek „${name.trim()}“ angelegt.`, 'success')
      navigate(`/libraries/${libraryId}`)
    } catch (err) {
      if (apiErrorCode(err) === 'PENDING_CONNECTION_UNUSABLE') {
        // expired or used up: back to „Quelle einrichten“, where the source is connected again
        setPending(null)
        setServiceAccountConfirmed(false)
        setActiveStep(steps.indexOf(STEP_SOURCE_SETUP))
        setError(PENDING_CONNECTION_UNUSABLE)
      } else {
        setError(err instanceof Error ? err.message : 'Bibliothek konnte nicht erstellt werden')
      }
      setSubmitting(false)
    }
  }

  const summaryIssues: SummaryIssue[] = [
    ...shownSourceIssues.map((issue) => ({
      message: issue.message,
      targetId: issue.field ? fieldDomId(formContext.idPrefix, issue.field) : undefined,
    })),
    ...otherIssues,
  ]

  const scheduleText = !uploadLibrary
    ? [
        scheduleFrequencyLabel(schedule.frequency),
        schedule.frequency === 'WEEKLY' ? scheduleWeekdayLabel(schedule.weekday) : null,
        schedule.frequency === 'DAILY' || schedule.frequency === 'WEEKLY'
          ? `${schedule.time} Uhr`
          : null,
      ]
        .filter(Boolean)
        .join(' · ')
    : ''
  const reviewSections: ReviewSection[] = [
    {
      title: 'Quelle',
      onChange: () => changeStep(configuration ? STEP_SOURCE_SETUP : STEP_SOURCE_TYPE),
      rows: [
        { label: 'Quellart', value: displayNameOf(sourceType) },
        ...(configuration && admitsProfiles
          ? [
              {
                label: 'Zugang',
                value: connection
                  ? `${connection.name}${privateLibrary ? ' (eigenes Konto)' : ''}`
                  : 'Eigene Adresse',
              },
            ]
          : []),
        ...(configuration?.summary?.(values, formContext) ?? []),
      ],
    },
    ...(!uploadLibrary
      ? [
          {
            title: 'Zeitplan',
            onChange: () => changeStep(STEP_SOURCE_SETUP),
            rows: [
              { label: 'Indizierung', value: scheduleText },
              { label: 'Sofort indizieren', value: startFirstRun ? 'Ja' : 'Nein' },
            ],
          },
        ]
      : []),
    {
      title: 'Name',
      onChange: () => changeStep(STEP_NAME),
      rows: [
        { label: 'Name', value: name.trim() },
        { label: 'Beschreibung', value: description.trim() || 'Keine' },
      ],
    },
    ...(steps.includes(STEP_SHARING)
      ? [
          {
            title: 'Freigaben',
            onChange: () => changeStep(STEP_SHARING),
            rows: [
              {
                label: 'Eigentümer',
                value:
                  ownerType === 'GROUP' ? `Gruppe „${selectedGroup?.name ?? '—'}“` : 'Mein Konto',
              },
              {
                label: 'Vorgemerkt',
                value:
                  pendingGrants.length === 0
                    ? 'Keine Freigaben'
                    : pendingGrants.length === 1
                      ? '1 Freigabe'
                      : `${pendingGrants.length} Freigaben`,
              },
            ],
          },
        ]
      : []),
  ]

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
        <PageHeading
          title="Neue Wissensbibliothek"
          documentTitle={`${summaryIssues.length > 0 ? 'Fehler: ' : ''}Schritt ${activeStep + 1} von ${steps.length}: ${stepHeadings[currentStep] ?? currentStep} – Neue Wissensbibliothek`}
          visuallyHidden
        />
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

        <ErrorSummary ref={summaryRef} issues={summaryIssues} />

        {/* A step's error is announced without moving the focus (WCAG 4.1.3). */}
        <Box role="alert" aria-live="polite">
          {error && (
            <Alert severity="error" sx={{ mb: 2 }}>
              {error}
            </Alert>
          )}
        </Box>

        {currentStep === STEP_SOURCE_TYPE && !sourceTypesLoaded && (
          <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
            Quellarten werden geladen …
          </Typography>
        )}

        {currentStep === STEP_SOURCE_TYPE && sourceTypesError && (
          <Alert
            severity="error"
            sx={{ mb: 2 }}
            action={
              <Button color="inherit" size="small" onClick={reloadSourceTypes}>
                Erneut versuchen
              </Button>
            }
          >
            Die Quellarten konnten nicht geladen werden. Versuchen Sie es in einem Moment erneut.
          </Alert>
        )}

        {currentStep === STEP_SOURCE_TYPE &&
          sourceTypesLoaded &&
          !sourceTypesError &&
          shownTypes.length > 0 && (
            <ChoiceTileGroup<SourceTypeKey>
              aria-label="Quellart wählen"
              value={sourceType}
              onChange={(next) => {
                setSourceType(next)
                setError(null)
              }}
              tiles={shownTypes.map((type) => ({
                value: type,
                label: displayNameOf(type),
                description: documentSourceTypeDescription(type),
                icon: <SourceTypeIcon sourceType={type} fontSize={22} />,
                disabledReason: missingFor(type),
              }))}
            />
          )}

        {currentStep === STEP_SOURCE_SETUP && (
          <CreateSourceSetupStep
            showConnectionSelect={showConnectionSelect}
            descriptor={descriptor}
            profileOptions={profileOptions}
            connectionChoice={connectionChoice}
            onChooseConnection={chooseConnection}
            consentProfile={consentProfile}
            pendingConnection={pendingConnection}
            serviceAccountConfirmed={serviceAccountConfirmed}
            onServiceAccountConfirmedChange={(checked) => {
              setServiceAccountConfirmed(checked)
              if (checked) setConfirmationMissing(false)
            }}
            confirmationMissing={confirmationMissing}
            connectingSource={connectingSource}
            onConnectSource={() => void handleConnectSource()}
            SourceForm={SourceForm}
            awaitsConsent={awaitsConsent}
            sourceFormRef={sourceFormRef}
            sourceIssues={shownSourceIssues}
            check={check}
            checkId={CHECK_ID}
            onTested={(result) => {
              if (result) applyTested(result.details)
            }}
            values={values}
            formContext={formContext}
            onSourceChange={changeSource}
            schedule={schedule}
            onScheduleChange={(patch) => {
              setSchedule((prev) => ({ ...prev, ...patch }))
              setError(null)
            }}
            confluenceRhythm={confluenceRhythm}
            startFirstRun={startFirstRun}
            onStartFirstRunChange={setStartFirstRun}
            firstRunHint={firstRunHint}
          />
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
                if (next.trim() !== '') setNameError(null)
              }}
              nameError={nameError}
              description={description}
              onDescriptionChange={setDescription}
              namePlaceholder="z. B. Rechtsquellen Soziales"
            />
          </Box>
        )}

        {currentStep === STEP_SHARING && (
          <CreateSharingStep
            ownerType={ownerType}
            onOwnerTypeChange={(next) => {
              setOwnerType(next)
              setError(null)
            }}
            myGroups={myGroups}
            selectedGroup={selectedGroup}
            onSelectedGroupChange={setSelectedGroup}
            connectsSource={consentProfile !== undefined}
            groupResponsible={groupResponsible}
            responsibleIsGroup={responsibleIsGroup}
            onResponsibleIsGroupChange={setResponsibleIsGroup}
            pendingGrants={pendingGrants}
            onPendingGrantsChange={setPendingGrants}
          />
        )}

        {currentStep === STEP_REVIEW && <CreateReviewStep sections={reviewSections} />}

        <LibraryCreateFooter
          activeStep={activeStep}
          stepCount={steps.length}
          submitting={submitting}
          pendingGrantCount={currentStep === STEP_SHARING ? pendingGrants.length : 0}
          nextLabel={returnToReview ? 'Zur Prüfung' : 'Weiter'}
          nextBusy={check.running}
          missingCapability={missingCapability}
          onCancel={() => void handleCancel()}
          onBack={() => {
            clearIssues()
            setReturnToReview(false)
            setActiveStep((s) => s - 1)
          }}
          onNext={() => void handleNext()}
          onCreate={() => void handleCreate()}
        />
      </Box>
    </Box>
  )
}
