import { useEffect, useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import FormControlLabel from '@mui/material/FormControlLabel'
import Switch from '@mui/material/Switch'
import Typography from '@mui/material/Typography'
import ChevronLeft from '@mui/icons-material/ChevronLeft'
import { useNavigate, useSearchParams } from 'react-router'
import PageHeading from '@/shared/ui/a11y/PageHeading'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import ChoiceTileGroup from '@/shared/ui/choice/ChoiceTileGroup'
import { CATALOG_ROUTE } from '@/shared/routes'
import { sourceTypeIcon as SourceTypeIcon } from '@/features/connection'
import { registeredSourceTypes, sourceRegistration } from '../components/sources/registry'
import type { SourceFormContext } from '../components/sources/types'
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
import { confirmAction } from '@/shared/stores/confirmStore'
import { useLibraryStore } from '../stores/libraryStore'
import { useIndexingStore } from '../stores/indexingStore'
import { useMyCapabilities } from '@/shared/session/useMyCapabilities'
import { useMyGroups } from '@/features/principal'
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
  restoredDraft,
  restoredSourceValues,
} from '../components/libraryCreateWizard'
import { useSourceTypeChoice } from '../components/useSourceTypeChoice'
import CreateSharingStep from '../components/CreateSharingStep'
import ConnectionProfileSelect from '../components/ConnectionProfileSelect'
import SourceConsentPanel from '../components/SourceConsentPanel'
import LibraryScheduleForm from '../components/LibraryScheduleForm'
import { scheduleFrequencyLabel } from '../components/scheduleLabels'
import ConnectionPage from '../components/stepwise/ConnectionPage'
import { checkSummary, fieldId, stageErrors } from '../components/stepwise/stepErrors'
import ErrorSummary, { type SummaryError } from '../components/stepwise/ErrorSummary'
import FieldList from '../components/stepwise/FieldList'
import ScopePicker from '../components/stepwise/ScopePicker'
import SummaryList, { type SummarySection } from '../components/stepwise/SummaryList'
import CheckResult from '../components/stepwise/CheckResult'
import { checkConnection, checkRights, useCheck } from '../components/stepwise/connectionCheck'
import type { FieldErrors, StepContext } from '../components/stepwise/types'

/** The steps of the wizard, one thing each; the key stands in the address (`?schritt=`). */
type StepKey = 'quellart' | 'verbindung' | 'umfang' | 'zeitplan' | 'name' | 'freigaben' | 'pruefen'

const STEP_LABELS: Record<StepKey, string> = {
  quellart: 'Quellart',
  verbindung: 'Verbindung',
  umfang: 'Umfang',
  zeitplan: 'Zeitplan',
  name: 'Name',
  freigaben: 'Freigaben',
  pruefen: 'Prüfen',
}

const ID = 'library-create'

/**
 * The creation wizard for knowledge libraries in the stepwise variant: one thing per page
 * (Quellart → Verbindung → Umfang → Zeitplan → Name → Freigaben → Prüfen). A step without
 * content for the source type is left out; the page „Prüfen“ lists every answer with „Ändern“,
 * which leads to the step and back. Each step is an entry in the browser history, so the
 * browser's back button goes one step back without losing anything.
 */
export default function LibraryCreatePage() {
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const createNewLibrary = useLibraryStore((s) => s.createNewLibrary)
  const triggerIndexing = useIndexingStore((s) => s.triggerIndexing)

  const [restored] = useState(restoredDraft)
  const draft = restored?.draft
  const [pageError, setPageError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
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
  const [sourceValues, setSourceValues] = useState<Record<SourceTypeKey, unknown>>(() =>
    restoredSourceValues(restored),
  )
  const [chosenConnections, setChosenConnections] = useState<Record<SourceTypeKey, string>>(
    draft?.chosenConnections ?? {},
  )
  const [schedule, setSchedule] = useState<LibraryScheduleValues>(
    () => draft?.schedule ?? scheduleValuesFrom(null),
  )
  const [startFirstRun, setStartFirstRun] = useState(draft?.startFirstRun ?? true)
  const [pendingGrants, setPendingGrants] = useState<PendingGrant[]>(draft?.pendingGrants ?? [])
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

  // validation shows after „Weiter“ on a step, and for a field left with a value in it
  const [attempted, setAttempted] = useState<Set<StepKey>>(new Set())
  const [touched, setTouched] = useState<Set<string>>(new Set())
  // „Ändern“ on the page „Prüfen“: the step leads back there
  const [returnToReview, setReturnToReview] = useState(false)
  // which button started a running check shows it; the other stays as it is
  const [advancing, setAdvancing] = useState(false)
  const summaryRef = useRef<HTMLDivElement>(null)
  const [focusSummary, setFocusSummary] = useState(0)

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
  const stepwise = configuration?.stepwise ?? null
  const scope = stepwise?.scope ?? null
  const valuesKey = configuration?.valuesKey ?? sourceType

  const descriptor = sourceTypes.find((d) => d.type === sourceType)
  const admitsProfiles =
    configuration !== null && descriptor !== undefined && descriptor.profileSupport !== 'FORBIDDEN'
  const profileOptions = useConnectionProfileOptions(admitsProfiles ? sourceType : null)
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
  const showConnectionSelect =
    admitsProfiles &&
    descriptor !== undefined &&
    !(
      profileOptions.loaded &&
      profileOptions.error === null &&
      profileOptions.options.length === 0 &&
      ownAddressAllowed(descriptor)
    )

  const steps: StepKey[] = uploadLibrary
    ? ['quellart', 'name', 'freigaben', 'pruefen']
    : [
        'quellart',
        'verbindung',
        ...(scope ? (['umfang'] as const) : []),
        'zeitplan',
        'name',
        ...(privateLibrary ? [] : (['freigaben'] as const)),
        'pruefen',
      ]
  const requested = searchParams.get('schritt') as StepKey | null
  const current: StepKey = requested && steps.includes(requested) ? requested : steps[0]
  const stepIndex = steps.indexOf(current)

  // A reload loses the entries; the wizard then starts over instead of showing a half-empty step.
  const initialized = useRef(false)
  useEffect(() => {
    if (initialized.current) return
    initialized.current = true
    const start: StepKey = restored ? 'verbindung' : 'quellart'
    if (searchParams.get('schritt') !== start) go(start, true)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  function go(step: StepKey, replace = false) {
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev)
        next.set('schritt', step)
        return next
      },
      { replace },
    )
    setPageError(null)
  }

  const values: unknown = withConnection(
    sourceValues[valuesKey] ?? configuration?.empty,
    connection,
  )
  const formContext: SourceFormContext = {
    mode: 'create',
    sourceType,
    idPrefix: ID,
    credentialsStored: false,
    connection,
    privateLibrary,
    pendingConnectionId: pendingConnection?.id,
  }
  const stepCtx: StepContext = {
    mode: 'create',
    sourceType,
    credentialsStored: false,
    replacingCredentials: false,
    fields: connectionFields(formContext),
    formContext,
  }

  function chooseConnection(next: string) {
    if (next === connectionChoice) return
    setChosenConnections((prev) => ({ ...prev, [sourceType]: next }))
    const nextOption = profileOptions.options.find((option) => option.id === profileOfChoice(next))
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
    setPageError(null)
  }

  function changeSource(patch: object) {
    const adjusted = stepwise?.adjust ? stepwise.adjust(patch as never, values as never) : patch
    setSourceValues((prev) => ({
      ...prev,
      [valuesKey]: { ...(values as object), ...withoutFixed(adjusted as object, connection) },
    }))
    setPageError(null)
  }

  const confluenceRhythm: ConfluenceFullSyncRhythm | undefined = configuration?.fullSyncRhythm
    ? { intervalDays: null, defaultDays: descriptor?.fullSyncIntervalDefaultDays ?? null }
    : undefined
  const typesUnavailable = !sourceTypesLoaded || sourceTypesError != null
  const requiredCapability: Capability = capabilityFor(sourceType)
  const missingCapability = nothingUsable
    ? NOTHING_CREATABLE
    : isMissing(requiredCapability)
      ? capabilityMissingMessage(requiredCapability)
      : releaseMissingFor(sourceType)

  // ---- errors and checks ------------------------------------------------------------------------

  const specs = stepwise ? stepwise.fields(values as never, stepCtx) : []
  const connectionToken = JSON.stringify({
    sourceType,
    connectionChoice,
    pending: pendingConnection?.id ?? null,
    fields: specs.map((spec) => (values as Record<string, unknown>)?.[spec.key] ?? null),
  })
  const connectionCheck = useCheck(connectionToken)
  const selectionToken = JSON.stringify({
    connectionToken,
    selection: scope ? scope.selection(values as never).map((node) => node.id) : [],
    whole: scope?.whole?.isWhole(values as never) ?? false,
  })
  const rightsCheck = useCheck(selectionToken)

  const filled = (key: string) =>
    String((values as Record<string, unknown>)?.[key] ?? '').trim() !== ''
  function visible(errors: FieldErrors, step: StepKey) {
    if (attempted.has(step)) return errors
    return Object.fromEntries(
      Object.entries(errors).filter(([key]) => touched.has(key) && filled(key)),
    ) as FieldErrors
  }

  const connectionProblems: FieldErrors = stepwise
    ? stepwise.connectionErrors(values as never, stepCtx)
    : {}
  const scopeProblems: FieldErrors = scope ? scope.errors(values as never, stepCtx) : {}
  const scheduleProblem = validateScheduleValues(schedule, confluenceRhythm)
  const nameProblem = name.trim() === '' ? 'Bitte einen Namen eingeben.' : null
  const groupProblem =
    !privateLibrary && ownerType === 'GROUP' && !selectedGroup ? 'Bitte eine Gruppe wählen.' : null

  const shownConnectionErrors: FieldErrors = {
    ...stageErrors(
      stepwise ?? ({ stageFields: { address: [], signIn: [] } } as never),
      specs,
      connectionCheck.outcome,
    ),
    ...visible(connectionProblems, 'verbindung'),
  }

  function connectionGate(): string | null {
    if (!configuration) return null
    if (connectionChoice === null) {
      return !profileOptions.loaded
        ? 'Die Zugänge werden noch geladen.'
        : selectable.length > 0
          ? 'Bitte wählen Sie einen Zugang.'
          : 'Für diese Quellart steht Ihnen kein Zugang zur Verfügung.'
    }
    if (awaitsConsent) return CONSENT_REQUIRED
    return null
  }

  function summaryFor(step: StepKey): SummaryError[] {
    if (!attempted.has(step)) return []
    const toField = (errors: FieldErrors) =>
      Object.entries(errors).map(([key, message]) => ({
        message: message as string,
        targetId: key === 'scope' ? `${ID}-field-scope` : fieldId(ID, key),
      }))
    switch (step) {
      case 'verbindung': {
        const gate = connectionGate()
        if (gate) return [{ message: gate }]
        const list: SummaryError[] = toField(connectionProblems)
        const outcome = connectionCheck.outcome
        if (list.length === 0 && outcome && !outcome.ok) {
          const failedKey = [
            ...(outcome.stages.address === 'failed' ? stepwise!.stageFields.address : []),
            ...(outcome.stages.signIn === 'failed' ? stepwise!.stageFields.signIn : []),
          ].find((key) => specs.some((spec) => spec.key === key))
          list.push({
            message: checkSummary(outcome),
            targetId: failedKey ? fieldId(ID, failedKey) : undefined,
          })
        }
        return list
      }
      case 'umfang': {
        const list: SummaryError[] = toField(scopeProblems)
        const outcome = rightsCheck.outcome
        if (list.length === 0 && outcome && !outcome.ok) {
          const failed = Object.keys(outcome.failedEntries ?? {}).length
          list.push({
            message:
              failed > 0
                ? `${failed} ${failed === 1 ? 'ausgewählter Eintrag ist' : 'ausgewählte Einträge sind'} nicht lesbar.`
                : checkSummary(outcome),
            targetId: `${ID}-field-scope`,
          })
        }
        return list
      }
      case 'zeitplan':
        return scheduleProblem
          ? [{ message: scheduleProblem, targetId: `${ID}-schedule-time` }]
          : []
      case 'name':
        return nameProblem ? [{ message: nameProblem, targetId: fieldId(ID, 'name') }] : []
      case 'freigaben':
        return groupProblem ? [{ message: groupProblem }] : []
      case 'pruefen':
        return reviewProblems()
      default:
        return []
    }
  }

  function reviewProblems(): SummaryError[] {
    const list: SummaryError[] = []
    const jump = (step: StepKey) => () => {
      setReturnToReview(true)
      setAttempted((prev) => new Set(prev).add(step))
      go(step)
    }
    if (configuration && (connectionGate() || Object.keys(connectionProblems).length > 0)) {
      list.push({ message: 'Die Verbindung ist unvollständig.', onSelect: jump('verbindung') })
    }
    if (scope && Object.keys(scopeProblems).length > 0) {
      list.push({ message: Object.values(scopeProblems)[0] as string, onSelect: jump('umfang') })
    }
    if (configuration && scheduleProblem)
      list.push({ message: scheduleProblem, onSelect: jump('zeitplan') })
    if (nameProblem) list.push({ message: nameProblem, onSelect: jump('name') })
    if (groupProblem) list.push({ message: groupProblem, onSelect: jump('freigaben') })
    if (missingCapability) list.push({ message: missingCapability })
    return list
  }

  const summary = summaryFor(current)
  useEffect(() => {
    if (focusSummary > 0) summaryRef.current?.focus()
  }, [focusSummary])

  // Focus follows the step (WCAG 2.4.3): after „Weiter“ it is on the heading of the new step.
  const stepHeadingRef = useRef<HTMLHeadingElement>(null)
  const firstRender = useRef(true)
  useEffect(() => {
    if (firstRender.current) {
      firstRender.current = false
      return
    }
    stepHeadingRef.current?.focus()
  }, [current])

  useEffect(() => {
    if (!restored) return
    forgetConsentIntent()
    stepHeadingRef.current?.focus()
  }, [restored])

  // ---- navigation ------------------------------------------------------------------------------

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

  async function handleConnectSource() {
    if (!consentProfile || connectingSource) return
    if (!serviceAccountConfirmed) {
      setConfirmationMissing(true)
      return
    }
    setConnectingSource(true)
    setPageError(null)
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
      setPageError(
        err instanceof Error && err.message
          ? err.message
          : 'Die Anmeldung beim Anbieter ließ sich nicht starten.',
      )
      setConnectingSource(false)
    }
  }

  function advance() {
    setPageError(null)
    if (returnToReview && current !== 'verbindung') {
      setReturnToReview(false)
      go('pruefen')
      return
    }
    go(steps[stepIndex + 1])
  }

  function fail(step: StepKey) {
    setAttempted((prev) => new Set(prev).add(step))
    setFocusSummary((n) => n + 1)
  }

  async function runConnectionCheck() {
    if (!configuration || !stepwise) return null
    const outcome = await connectionCheck.run(() =>
      checkConnection(configuration, stepwise, values, stepCtx),
    )
    if (outcome.patch) changeSource(outcome.patch)
    return outcome
  }

  const handleNext = async () => {
    setAdvancing(true)
    try {
      await advanceStep()
    } finally {
      setAdvancing(false)
    }
  }

  const advanceStep = async () => {
    if (current === 'quellart') {
      if (typesUnavailable) {
        setPageError(sourceTypesError ?? 'Die Quellarten werden noch geladen.')
        return
      }
      if (nothingUsable) return
      advance()
      return
    }
    if (current === 'verbindung') {
      if (connectionGate() || Object.keys(connectionProblems).length > 0) {
        fail('verbindung')
        return
      }
      const outcome = connectionCheck.outcome ?? (await runConnectionCheck())
      if (!outcome || !outcome.ok) {
        fail('verbindung')
        return
      }
      if (returnToReview && !scope) {
        setReturnToReview(false)
        go('pruefen')
        return
      }
      advance()
      return
    }
    if (current === 'umfang' && configuration && stepwise) {
      if (Object.keys(scopeProblems).length > 0) {
        fail('umfang')
        return
      }
      const outcome =
        rightsCheck.outcome ??
        (await rightsCheck.run(() => checkRights(configuration, stepwise, values, stepCtx)))
      if (!outcome.ok) {
        fail('umfang')
        return
      }
      advance()
      return
    }
    if (current === 'zeitplan') {
      if (scheduleProblem) {
        fail('zeitplan')
        return
      }
      if (!nameTouched && name.trim() === '' && configuration) {
        setName(configuration.nameFromSource(values))
      }
      advance()
      return
    }
    if (current === 'name') {
      if (nameProblem) {
        fail('name')
        return
      }
      advance()
      return
    }
    if (current === 'freigaben') {
      if (groupProblem) {
        fail('freigaben')
        return
      }
      advance()
    }
  }

  const handleCreate = async () => {
    if (submitting) return
    if (reviewProblems().length > 0) {
      fail('pruefen')
      return
    }
    setSubmitting(true)
    setPageError(null)
    try {
      const source = configuration
        ? payloadUnder(configuration.toPayload(values), connectionFields(formContext))
        : {}
      const scheduled = !uploadLibrary
        ? scheduleUpdateFrom(schedule, confluenceRhythm, 'create')
        : undefined
      const sourceSettings =
        source.sourceSettings || scheduled?.sourceSettings
          ? { ...source.sourceSettings, ...scheduled?.sourceSettings }
          : undefined
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
        await triggerIndexing(libraryId, sourceType)
      }
      forgetConsentIntent()
      navigate(`/libraries/${libraryId}`)
    } catch (err) {
      if (apiErrorCode(err) === 'PENDING_CONNECTION_UNUSABLE') {
        setPending(null)
        setServiceAccountConfirmed(false)
        go('verbindung')
        setPageError(PENDING_CONNECTION_UNUSABLE)
      } else {
        setPageError(err instanceof Error ? err.message : 'Bibliothek konnte nicht erstellt werden')
      }
      setSubmitting(false)
    }
  }

  // ---- texts -----------------------------------------------------------------------------------

  const typeName = displayNameOf(sourceType)
  const headings: Record<StepKey, string> = {
    quellart: 'Woher kommt das Wissen?',
    verbindung: stepwise?.connectionHeading ?? `Verbindung zu ${typeName}`,
    umfang: scope?.heading ?? 'Umfang',
    zeitplan: 'Wann soll die Bibliothek aktualisiert werden?',
    name: 'Wie soll die Bibliothek heißen?',
    freigaben: 'Wem gehört die Bibliothek?',
    pruefen: 'Prüfen und anlegen',
  }
  const stepCaption = `Schritt ${stepIndex + 1} von ${steps.length}`
  const documentTitle = `${summary.length > 0 ? 'Fehler: ' : ''}${stepCaption}: ${STEP_LABELS[current]} – Neue Wissensbibliothek`
  const firstRunHint =
    configuration?.firstRunHint ??
    'Der erste Lauf liest die Quelle vollständig ein; sein Stand bleibt auf der Detailseite sichtbar.'
  const ownerLabel = privateLibrary
    ? 'Nur Sie (private Bibliothek)'
    : ownerType === 'GROUP'
      ? `Gruppe ${selectedGroup?.name ?? '–'}`
      : 'Mein Konto'

  function reviewSections(): SummarySection[] {
    const change = (step: StepKey) => () => {
      setReturnToReview(true)
      go(step)
    }
    const sections: SummarySection[] = [
      {
        title: 'Quelle',
        rows: [
          { label: 'Quellart', value: typeName },
          ...(configuration && showConnectionSelect
            ? [
                {
                  label: 'Zugang',
                  value: chosenProfile ? chosenProfile.name : 'Eigene Adresse',
                },
              ]
            : []),
        ],
        onChange: () => {
          setReturnToReview(false)
          go('quellart')
        },
      },
    ]
    if (stepwise) {
      const rows = stepwise.connectionRows(values as never, { credentialsStored: false })
      if (pendingConnection) {
        rows.push({ label: 'Verbunden als', value: pendingConnection.accountLabel ?? 'Verbunden' })
      }
      if (rows.length > 0)
        sections.push({ title: 'Verbindung', rows, onChange: change('verbindung') })
    }
    if (scope && stepwise?.scopeRows) {
      sections.push({
        title: 'Umfang',
        rows: stepwise.scopeRows(values as never),
        onChange: change('umfang'),
      })
    }
    if (configuration) {
      sections.push({
        title: 'Zeitplan',
        rows: [
          { label: 'Häufigkeit', value: scheduleFrequencyLabel(schedule.frequency) },
          {
            label: 'Erster Lauf',
            value: startFirstRun ? 'Gleich nach dem Anlegen' : 'Nach Zeitplan oder von Hand',
          },
        ],
        onChange: change('zeitplan'),
      })
    }
    sections.push({
      title: 'Name',
      rows: [
        { label: 'Name', value: name.trim() || '–' },
        { label: 'Beschreibung', value: description.trim() || 'Keine' },
      ],
      onChange: change('name'),
    })
    if (!privateLibrary) {
      sections.push({
        title: 'Freigaben',
        rows: [
          { label: 'Eigentümer', value: ownerLabel },
          {
            label: 'Freigaben',
            value:
              pendingGrants.length === 0
                ? 'Keine'
                : pendingGrants.length === 1
                  ? '1 vorgemerkt'
                  : `${pendingGrants.length} vorgemerkt`,
          },
        ],
        onChange: change('freigaben'),
      })
    }
    return sections
  }

  // ---- render ----------------------------------------------------------------------------------

  const isLast = current === 'pruefen'
  const busy = advancing

  return (
    <Box sx={{ flexGrow: 1, overflowY: 'auto', p: { xs: 2, md: 5 } }}>
      <Box sx={{ maxWidth: 720 }}>
        <PageHeading title="Neue Wissensbibliothek" documentTitle={documentTitle} visuallyHidden />
        <Box sx={{ minHeight: 32, mb: 2 }}>
          {stepIndex > 0 && (
            <Button
              variant="text"
              startIcon={<ChevronLeft />}
              onClick={() => go(steps[stepIndex - 1])}
              disabled={submitting}
              sx={{ ml: -1 }}
            >
              Zurück
            </Button>
          )}
        </Box>
        <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mb: 0.5 }}>
          {`${stepCaption} · Neue Wissensbibliothek`}
        </Typography>
        <Typography
          ref={stepHeadingRef}
          component="h2"
          tabIndex={-1}
          sx={{
            fontSize: { xs: 22, sm: 26 },
            fontWeight: 600,
            mb: 3,
            outline: 'none',
            hyphens: 'auto',
          }}
          lang="de"
        >
          {headings[current]}
        </Typography>

        {missingCapability && (current === 'quellart' || isLast) && (
          <Alert severity="info" sx={{ mb: 2 }} id="library-create-capability-hint">
            {missingCapability}
          </Alert>
        )}

        <ErrorSummary ref={summaryRef} errors={summary} />
        <Box role="alert" aria-live="polite">
          {pageError && (
            <Alert severity="error" sx={{ mb: 2 }}>
              {pageError}
            </Alert>
          )}
        </Box>

        {current === 'quellart' && (
          <>
            {!sourceTypesLoaded && (
              <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
                Quellarten werden geladen …
              </Typography>
            )}
            {sourceTypesError && (
              <Box>
                <Typography sx={{ fontSize: 14, mb: 1 }}>
                  Die Quellarten konnten nicht geladen werden.
                </Typography>
                <Button variant="outlined" onClick={reloadSourceTypes}>
                  Erneut versuchen
                </Button>
              </Box>
            )}
            {sourceTypesLoaded && !sourceTypesError && shownTypes.length > 0 && (
              <ChoiceTileGroup<SourceTypeKey>
                aria-label="Quellart wählen"
                value={sourceType}
                onChange={(next) => {
                  setSourceType(next)
                  setPageError(null)
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
          </>
        )}

        {current === 'verbindung' && configuration && stepwise && (
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
            {showConnectionSelect && descriptor && (
              <ConnectionProfileSelect
                descriptor={descriptor}
                state={profileOptions}
                value={connectionChoice}
                onChange={chooseConnection}
                offerOwnAddress
                offerPrivate
                idPrefix={ID}
              />
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
            {connectionChoice !== null && !awaitsConsent && (
              <ConnectionPage
                key={connectionChoice}
                stepwise={stepwise}
                specs={specs}
                values={values}
                onChange={changeSource}
                ctx={stepCtx}
                idPrefix={ID}
                errors={shownConnectionErrors}
                onLeave={(key) => setTouched((prev) => new Set(prev).add(key))}
                outcome={connectionCheck.outcome}
                checking={connectionCheck.running && !advancing}
                onCheck={() => void runConnectionCheck()}
                onProceedAnyway={advance}
                onReplacingChange={() => {}}
              />
            )}
          </Box>
        )}

        {current === 'umfang' && scope && (
          <Box>
            <ScopePicker
              key={connectionToken}
              scope={scope}
              values={values as never}
              onChange={changeSource}
              ctx={stepCtx}
              idPrefix={ID}
              error={visible(scopeProblems, 'umfang').scope}
              failedEntries={rightsCheck.outcome?.failedEntries}
            />
            {rightsCheck.outcome && (
              <Box role="status" aria-live="polite" sx={{ mt: 2 }}>
                <CheckResult outcome={rightsCheck.outcome} />
                {!rightsCheck.outcome.ok && (
                  <Button variant="text" size="small" onClick={advance} sx={{ mt: 1, ml: -1 }}>
                    Ohne erfolgreiche Prüfung weiter
                  </Button>
                )}
              </Box>
            )}
          </Box>
        )}

        {current === 'zeitplan' && configuration && (
          <Box sx={{ maxWidth: 560 }}>
            <LibraryScheduleForm
              idPrefix={`${ID}-schedule`}
              values={schedule}
              onChange={(patch) => setSchedule((prev) => ({ ...prev, ...patch }))}
              confluence={confluenceRhythm}
            />
            <FormControlLabel
              sx={{ mt: 3 }}
              control={
                <Switch
                  checked={startFirstRun}
                  onChange={(e) => setStartFirstRun(e.target.checked)}
                />
              }
              label="Erste Indizierung gleich nach dem Anlegen"
            />
            <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 0.5 }}>
              {startFirstRun
                ? firstRunHint
                : 'Sonst beginnt die Indizierung erst über „Jetzt indizieren“ oder den Zeitplan.'}
            </Typography>
          </Box>
        )}

        {current === 'name' && (
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2.5 }}>
            {uploadLibrary && (
              <Typography sx={{ fontSize: 13.5, color: 'text.secondary', maxWidth: 560 }}>
                Dokumente laden Sie nach dem Anlegen auf der Detailseite hoch.
              </Typography>
            )}
            {privateLibrary && (
              <Typography
                data-testid="library-create-private-note"
                sx={{ fontSize: 13.5, color: 'text.secondary', maxWidth: 560 }}
              >
                {PRIVATE_LIBRARY_NOTE} Sie läuft über Ihr verbundenes Konto auf dem Zugang „
                {connection?.name}“.
              </Typography>
            )}
            <FieldList
              specs={[
                {
                  key: 'name',
                  label: 'Name',
                  kind: 'text',
                  placeholder: 'z. B. Rechtsquellen Soziales',
                },
                { key: 'description', label: 'Beschreibung', kind: 'multiline', optional: true },
              ]}
              values={{ name, description }}
              onChange={(patch) => {
                if ('name' in patch) {
                  setNameTouched(true)
                  setName(String(patch.name))
                }
                if ('description' in patch) setDescription(String(patch.description))
              }}
              errors={attempted.has('name') && nameProblem ? { name: nameProblem } : {}}
              idPrefix={ID}
              mode="create"
              credentialsStored={false}
              replacing={false}
              onReplacingChange={() => {}}
            />
          </Box>
        )}

        {current === 'freigaben' && (
          <CreateSharingStep
            ownerType={ownerType}
            onOwnerTypeChange={(next) => setOwnerType(next)}
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

        {current === 'pruefen' && <SummaryList sections={reviewSections()} />}

        <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, mt: 5, flexWrap: 'wrap' }}>
          {isLast ? (
            <BusyButton
              variant="contained"
              busy={submitting}
              busyAnnouncement="Bibliothek wird angelegt"
              onClick={() => void handleCreate()}
            >
              {submitting ? 'Wird angelegt …' : 'Bibliothek anlegen'}
            </BusyButton>
          ) : (
            <BusyButton
              variant="contained"
              busy={busy}
              busyAnnouncement="Wird geprüft"
              onClick={() => void handleNext()}
            >
              {busy ? 'Wird geprüft …' : 'Weiter'}
            </BusyButton>
          )}
          <Button variant="text" onClick={() => void handleCancel()} disabled={submitting}>
            Abbrechen
          </Button>
        </Box>
      </Box>
    </Box>
  )
}
