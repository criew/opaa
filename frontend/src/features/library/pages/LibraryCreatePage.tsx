import { useEffect, useRef, useState } from 'react'
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
import { issueAt, type SourceIssue } from '../utils/sourceIssue'
import IssueSummary from '../components/setup/IssueSummary'
import { ConnectionGateContext, useConnectionGateHost } from '../components/setup/connectionGate'
import CreateReviewStep from '../components/CreateReviewStep'
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
  // every issue of the last „Weiter": shown at its field and listed above the step
  const [issues, setIssues] = useState<SourceIssue[]>([])
  const sourceFormRef = useRef<HTMLDivElement>(null)
  const stepRef = useRef<HTMLDivElement>(null)
  const summaryRef = useRef<HTMLDivElement>(null)
  const [checking, setChecking] = useState(false)
  // after „Ändern" on the review step, „Weiter" leads straight back to it
  const [returnToReview, setReturnToReview] = useState(false)
  const { gate, ensure } = useConnectionGateHost()
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
    setIssues([])
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

  /** Shows `found` at their fields and above the step, and moves the focus to the list. */
  function report(found: SourceIssue[]) {
    setError(null)
    setIssues(found)
    requestAnimationFrame(() => summaryRef.current?.focus())
  }

  const SELECTION_FIELDS = ['folders', 'spaces', 'scopes', 'libraries']

  const handleNext = async () => {
    if (checking) return
    if (currentStep === STEP_SOURCE_TYPE && typesUnavailable) {
      setError(sourceTypesError ?? 'Die Quellarten werden noch geladen')
      return
    }
    // the hint above the step bar already says why nothing can be created
    if (currentStep === STEP_SOURCE_TYPE && nothingUsable) return
    if (currentStep === STEP_SOURCE_SETUP && configuration) {
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
      const found =
        configuration.validateAll?.(values, formContext) ??
        [configuration.validate(values, formContext)].filter(
          (issue): issue is SourceIssue => issue !== null,
        )
      const scheduleError = validateScheduleValues(schedule, confluenceRhythm)
      const scheduleIssues = scheduleError ? [issueAt('schedule', scheduleError)] : []
      const connectionIssues = found.filter(
        (issue) => !issue.field || !SELECTION_FIELDS.includes(issue.field),
      )
      if (connectionIssues.length > 0) {
        report([...connectionIssues, ...scheduleIssues])
        return
      }
      // the connection is tested on „Weiter" unless a passed result for these fields stands
      setChecking(true)
      const connected = await ensure()
      setChecking(false)
      if (!connected) {
        report([
          issueAt(
            'connection',
            'Die Verbindung steht nicht; das Ergebnis des Tests steht unter „Verbindung“.',
          ),
          ...scheduleIssues,
        ])
        return
      }
      if (found.length > 0 || scheduleIssues.length > 0) {
        report([...found, ...scheduleIssues])
        return
      }
      // Where the source provides it, the name comes from it - as long as nobody has typed one.
      if (!nameTouched && name.trim() === '') {
        setName(configuration.nameFromSource(values))
      }
    }
    if (currentStep === STEP_NAME && name.trim() === '') {
      report([issueAt('name', 'Bitte einen Namen eingeben')])
      return
    }
    if (currentStep === STEP_SHARING && ownerType === 'GROUP' && !selectedGroup) {
      setError('Bitte eine Gruppe auswählen')
      return
    }
    setError(null)
    setIssues([])
    if (returnToReview) {
      setReturnToReview(false)
      setActiveStep(steps.indexOf(STEP_REVIEW))
      return
    }
    setActiveStep((s) => s + 1)
  }

  const handleCreate = async () => {
    if (submitting) return
    if (missingCapability) {
      setError(missingCapability)
      return
    }
    if (name.trim() === '') {
      setActiveStep(steps.indexOf(STEP_NAME))
      report([issueAt('name', 'Bitte einen Namen eingeben')])
      return
    }
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

  const firstRunHint =
    configuration?.firstRunHint ??
    'Der erste Lauf liest die Quelle vollständig ein; sein Stand bleibt auf der Detailseite sichtbar.'
  const SourceForm = configuration?.Form

  return (
    <Box sx={{ flexGrow: 1, overflowY: 'auto', p: { xs: 2.5, md: 5 } }}>
      <Box sx={{ maxWidth: currentStep === STEP_SOURCE_SETUP ? 1040 : 720 }} ref={stepRef}>
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mb: 0.5 }}>
          Neue Wissensbibliothek
        </Typography>
        <PageHeading
          title="Neue Wissensbibliothek"
          documentTitle={`${issues.length > 0 ? 'Fehler: ' : ''}Schritt ${activeStep + 1} von ${steps.length}: ${stepHeadings[currentStep] ?? currentStep} – Neue Wissensbibliothek`}
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

        {/* A step's error is announced without moving the focus (WCAG 4.1.3). */}
        <Box role="alert" aria-live="polite">
          {error && (
            <Alert severity="error" sx={{ mb: 2 }}>
              {error}
            </Alert>
          )}
        </Box>

        <IssueSummary ref={summaryRef} issues={issues} scope={() => stepRef.current} />

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
          <ConnectionGateContext value={gate}>
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
              issues={issues}
              values={values}
              formContext={formContext}
              onSourceChange={(patch: object) => {
                setSourceValues((prev) => ({
                  ...prev,
                  [valuesKey]: { ...(values as object), ...withoutFixed(patch, connection) },
                }))
                setError(null)
                // an issue goes once its field changes; the rest stays until the next „Weiter"
                setIssues((prev) => prev.filter((issue) => !issue.field || !(issue.field in patch)))
              }}
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
          </ConnectionGateContext>
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
                setIssues((prev) => prev.filter((issue) => issue.field !== 'name'))
              }}
              nameError={issues.find((issue) => issue.field === 'name')?.message ?? null}
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

        {currentStep === STEP_REVIEW && (
          <CreateReviewStep
            sourceTypeName={displayNameOf(sourceType)}
            connectionName={connection?.name ?? null}
            summary={configuration?.summary?.(values, formContext) ?? null}
            schedule={uploadLibrary ? null : schedule}
            startFirstRun={startFirstRun}
            name={name}
            description={description}
            owner={
              privateLibrary
                ? 'Nur Sie (private Bibliothek)'
                : ownerType === 'GROUP'
                  ? `Gruppe „${selectedGroup?.name ?? '–'}“`
                  : 'Ihr Konto'
            }
            grantCount={privateLibrary ? null : pendingGrants.length}
            steps={{
              sourceType: steps.indexOf(STEP_SOURCE_TYPE),
              setup: steps.includes(STEP_SOURCE_SETUP) ? steps.indexOf(STEP_SOURCE_SETUP) : null,
              name: steps.indexOf(STEP_NAME),
              sharing: steps.includes(STEP_SHARING) ? steps.indexOf(STEP_SHARING) : null,
            }}
            onChange={(step) => {
              setReturnToReview(step !== steps.indexOf(STEP_SOURCE_TYPE))
              setError(null)
              setIssues([])
              setActiveStep(step)
            }}
          />
        )}

        <LibraryCreateFooter
          activeStep={activeStep}
          stepCount={steps.length}
          submitting={submitting}
          pendingGrantCount={currentStep === STEP_SHARING ? pendingGrants.length : 0}
          checking={checking}
          returnToReview={returnToReview}
          missingCapability={missingCapability}
          onCancel={() => void handleCancel()}
          onBack={() => {
            setError(null)
            setIssues([])
            setActiveStep((s) => s - 1)
          }}
          onNext={() => void handleNext()}
          onCreate={() => void handleCreate()}
        />
      </Box>
    </Box>
  )
}
