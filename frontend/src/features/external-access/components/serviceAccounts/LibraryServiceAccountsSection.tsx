import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent, type Ref } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import FormControl from '@mui/material/FormControl'
import FormControlLabel from '@mui/material/FormControlLabel'
import FormHelperText from '@mui/material/FormHelperText'
import FormLabel from '@mui/material/FormLabel'
import Radio from '@mui/material/Radio'
import RadioGroup from '@mui/material/RadioGroup'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type {
  LibraryServiceAccountConsentResponse,
  ServiceAccountChoiceResponse,
} from '@/shared/api/types'
import { confirmAction } from '@/shared/stores/confirmStore'
import { notify } from '@/shared/stores/notificationStore'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import { TextAction } from '@/shared/ui/DetailSection'
import DialogTitleWithClose from '@/shared/ui/DialogTitleWithClose'
import ListSearch from '@/shared/ui/ListSearch'
import RowMenu from '@/shared/ui/RowMenu'
import StateDot from '@/shared/ui/StateDot'
import ValueRow from '@/shared/ui/ValueRow'
import {
  listLibraryServiceAccountConsents,
  listServiceAccountChoices,
  revokeLibraryServiceAccountConsent,
  setLibraryServiceAccountConsent,
} from '../../api/serviceAccountApi'
import { toDateInputValue } from '../tokenLabels'
import { Ending, ExpiredOn } from './ServiceAccountParts'
import { useErrorTitle } from './serviceAccountHooks'
import { REMOVE_CONSENT_CONSEQUENCE, formatDate, soonLabel } from './serviceAccountLabels'
import LoadError from '@/shared/ui/states/LoadError'
import LoadingState from '@/shared/ui/states/LoadingState'

/** Up to this many choices stand as a radio list without search (guidelines 5.11). */
const DIRECT_CHOICES = 7

/** End of the chosen day in the device's zone, like the release of a library. */
function toExpiresAt(dateInput: string): string {
  return new Date(`${dateInput}T23:59:59.999`).toISOString()
}

/** One day less than the bound, so the end of the chosen day stays inside it. */
function latestDate(maxDays: number): string {
  const latest = new Date()
  latest.setDate(latest.getDate() + maxDays - 1)
  return toDateInputValue(latest)
}

function tomorrow(): string {
  return toDateInputValue(new Date(Date.now() + 86_400_000))
}

function useConsents(libraryId: string) {
  const [consents, setConsents] = useState<LibraryServiceAccountConsentResponse[] | null>(null)
  const [maxDays, setMaxDays] = useState(365)
  const [error, setError] = useState<string | null>(null)
  const reload = useCallback(
    () =>
      listLibraryServiceAccountConsents(libraryId)
        .then((loaded) => {
          setConsents(loaded.consents)
          setMaxDays(loaded.maxConsentDays)
          setError(null)
        })
        .catch((err: unknown) =>
          setError(
            err instanceof Error ? err.message : 'Die Dienstkonten konnten nicht geladen werden.',
          ),
        ),
    [libraryId],
  )
  useEffect(() => {
    void reload()
  }, [reload])
  return { consents, maxDays, error, reload }
}

/** The active accounts that may still be named here; one already listed is changed in its row. */
function useChoices(libraryId: string, listed: readonly string[]) {
  const [choices, setChoices] = useState<ServiceAccountChoiceResponse[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const reload = useCallback(
    () =>
      listServiceAccountChoices(libraryId)
        .then((loaded) => {
          setChoices(loaded)
          setError(null)
        })
        .catch((err: unknown) =>
          setError(err instanceof Error ? err.message : 'Die Auswahl konnte nicht geladen werden.'),
        ),
    [libraryId],
  )
  useEffect(() => {
    void reload()
  }, [reload])
  const open = useMemo(
    () => choices?.filter((choice) => !listed.includes(choice.id)) ?? null,
    [choices, listed],
  )
  return { choices: open, error, reload }
}

/** The state of one consent: „bis <Datum>“, „Abgelaufen“ or the account „Deaktiviert“. */
function ConsentState({ consent }: { consent: LibraryServiceAccountConsentResponse }) {
  if (!consent.serviceAccountActive) {
    return <StateDot tone="neutral" label="Deaktiviert" />
  }
  if (consent.status === 'EXPIRED') {
    return <ExpiredOn value={consent.expiresAt} />
  }
  return (
    <span>
      bis <Ending value={consent.expiresAt} />
    </span>
  )
}

async function confirmRemove(
  libraryId: string,
  consent: LibraryServiceAccountConsentResponse,
  onDone: () => void,
) {
  const confirmed = await confirmAction({
    question: `„${consent.serviceAccountName}“ entfernen?`,
    consequence: REMOVE_CONSENT_CONSEQUENCE,
    confirmLabel: 'Entfernen',
    tone: 'danger',
  })
  if (!confirmed) return
  try {
    await revokeLibraryServiceAccountConsent(libraryId, consent.serviceAccountId)
    notify(`„${consent.serviceAccountName}“ hat keinen Zugriff mehr.`, 'success')
    onDone()
  } catch (err) {
    notify(err instanceof Error ? err.message : 'Das Entfernen ist fehlgeschlagen.', 'error')
  }
}

/** The consents as rows: name, state, „⋯“ with „Frist ändern“ and „Entfernen“. */
function ConsentRows({
  libraryId,
  consents,
  onChangeDate,
  onChanged,
}: {
  libraryId: string
  consents: LibraryServiceAccountConsentResponse[]
  onChangeDate: (consent: LibraryServiceAccountConsentResponse) => void
  onChanged: () => void
}) {
  return (
    <Box
      component="ul"
      aria-label="Dienstkonten mit Zugriff"
      sx={{ listStyle: 'none', p: 0, m: 0, borderTop: 1, borderColor: 'divider' }}
    >
      {consents.map((consent) => (
        <Box
          component="li"
          key={consent.serviceAccountId}
          sx={{
            display: 'grid',
            gridTemplateColumns: 'minmax(0, 1fr) auto',
            alignItems: 'center',
            columnGap: 1.5,
            py: 1.25,
            borderBottom: 1,
            borderColor: 'divider',
          }}
        >
          <Box sx={{ minWidth: 0 }}>
            <Typography sx={{ fontSize: 13.5, fontWeight: 600, overflowWrap: 'anywhere' }}>
              {consent.serviceAccountName}
            </Typography>
            <Typography component="div" sx={{ fontSize: 12.5, color: 'text.secondary' }}>
              <ConsentState consent={consent} />
            </Typography>
          </Box>
          <RowMenu
            name={consent.serviceAccountName}
            items={[
              consent.serviceAccountActive && {
                label: 'Frist ändern',
                onClick: () => onChangeDate(consent),
              },
              {
                label: 'Entfernen',
                danger: true,
                onClick: () => void confirmRemove(libraryId, consent, onChanged),
              },
            ]}
          />
        </Box>
      ))}
    </Box>
  )
}

/** Date field „Zugriff bis“ with the bound as its only help text. */
function UntilField({
  value,
  onChange,
  maxDays,
  error,
  inputRef,
}: {
  value: string
  onChange: (value: string) => void
  maxDays: number
  error?: string
  inputRef?: Ref<HTMLInputElement>
}) {
  return (
    <TextField
      label="Zugriff bis"
      type="date"
      value={value}
      onChange={(event) => onChange(event.target.value)}
      inputRef={inputRef}
      error={Boolean(error)}
      helperText={error ?? `höchstens ${maxDays} Tage`}
      size="small"
      sx={{ width: 220, maxWidth: '100%' }}
      slotProps={{
        inputLabel: { shrink: true },
        htmlInput: { min: tomorrow(), max: latestDate(maxDays) },
      }}
    />
  )
}

/** „Frist ändern“: the running end prefilled. */
interface ChangeDateDialogProps {
  libraryId: string
  consent: LibraryServiceAccountConsentResponse | null
  maxDays: number
  onClose: () => void
  onSaved: () => void
}

function ChangeDateDialog(props: ChangeDateDialogProps) {
  if (!props.consent) return null
  return <ChangeDateForm key={props.consent.serviceAccountId} {...props} consent={props.consent} />
}

function ChangeDateForm({
  libraryId,
  consent,
  maxDays,
  onClose,
  onSaved,
}: ChangeDateDialogProps & { consent: LibraryServiceAccountConsentResponse }) {
  const [date, setDate] = useState(
    consent.status === 'ACTIVE' ? toDateInputValue(new Date(consent.expiresAt)) : '',
  )
  const [error, setError] = useState<string | undefined>()
  const [saving, setSaving] = useState(false)
  const ref = useRef<HTMLInputElement>(null)
  useErrorTitle(Boolean(error))

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!date) {
      setError('Bitte ein Datum angeben.')
      ref.current?.focus()
      return
    }
    setSaving(true)
    try {
      await setLibraryServiceAccountConsent(libraryId, consent.serviceAccountId, toExpiresAt(date))
      onSaved()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Das Speichern ist fehlgeschlagen.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <Dialog
      open
      onClose={() => !saving && onClose()}
      fullWidth
      maxWidth="xs"
      aria-labelledby="consent-date-title"
    >
      <form noValidate onSubmit={(event) => void submit(event)}>
        <DialogTitle id="consent-date-title">Frist ändern</DialogTitle>
        <DialogContent>
          <Typography sx={{ fontSize: 13.5, mb: 2, overflowWrap: 'anywhere' }}>
            {consent.serviceAccountName}
          </Typography>
          <UntilField
            value={date}
            onChange={setDate}
            maxDays={maxDays}
            error={error}
            inputRef={ref}
          />
        </DialogContent>
        <DialogActions>
          <Button variant="text" onClick={onClose} disabled={saving}>
            Abbrechen
          </Button>
          <BusyButton
            type="submit"
            variant="contained"
            busy={saving}
            busyAnnouncement="Wird gespeichert"
          >
            Speichern
          </BusyButton>
        </DialogActions>
      </form>
    </Dialog>
  )
}

/**
 * „Aufnehmen“ at the top of the dialog: the active accounts not yet listed as a radio list (one
 * as a plain line, from eight on with a search), the end and the button.
 */
function AddConsentForm({
  libraryId,
  listed,
  maxDays,
  onSaved,
}: {
  libraryId: string
  listed: readonly string[]
  maxDays: number
  onSaved: (name: string) => void
}) {
  const { choices, error: loadError, reload } = useChoices(libraryId, listed)
  const [query, setQuery] = useState('')
  const [chosen, setChosen] = useState('')
  const [date, setDate] = useState('')
  const [errors, setErrors] = useState<{ choice?: string; date?: string }>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const dateRef = useRef<HTMLInputElement>(null)
  const groupRef = useRef<HTMLDivElement>(null)
  useErrorTitle(Boolean(errors.choice || errors.date))

  const single = choices?.length === 1 ? choices[0] : null
  const filtered = useMemo(() => {
    const needle = query.trim().toLocaleLowerCase('de')
    return (choices ?? []).filter((choice) => choice.name.toLocaleLowerCase('de').includes(needle))
  }, [choices, query])

  // a choice the search hides is no choice any more: „Aufnehmen“ never names an invisible account
  function search(value: string) {
    setQuery(value)
    const needle = value.trim().toLocaleLowerCase('de')
    const kept = (choices ?? []).some(
      (choice) => choice.id === chosen && choice.name.toLocaleLowerCase('de').includes(needle),
    )
    if (!kept) setChosen('')
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    const id = single?.id ?? chosen
    const found: typeof errors = {}
    if (!id) found.choice = 'Bitte ein Dienstkonto auswählen.'
    if (!date) found.date = 'Bitte ein Datum angeben.'
    setErrors(found)
    if (found.choice) return groupRef.current?.querySelector('input')?.focus()
    if (found.date) return dateRef.current?.focus()
    setSaving(true)
    setFormError(null)
    try {
      await setLibraryServiceAccountConsent(libraryId, id, toExpiresAt(date))
      onSaved((choices ?? []).find((choice) => choice.id === id)?.name ?? '')
    } catch (err) {
      setFormError(err instanceof Error ? err.message : 'Das Aufnehmen ist fehlgeschlagen.')
    } finally {
      setSaving(false)
    }
  }

  if (loadError) {
    return (
      <LoadError
        message="Die wählbaren Dienstkonten konnten nicht geladen werden."
        detail={loadError}
        onRetry={() => void reload()}
      />
    )
  }
  if (choices === null) return <LoadingState shape="rows" count={2} />
  if (choices.length === 0) {
    return (
      <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
        Kein weiteres aktives Dienstkonto. Dienstkonten legt die Systemverwaltung an.
      </Typography>
    )
  }

  return (
    <Box component="form" noValidate onSubmit={(event) => void submit(event)}>
      <Stack spacing={2}>
        {formError && <Alert severity="error">{formError}</Alert>}
        {single ? (
          <Box>
            <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>Dienstkonto</Typography>
            <Typography sx={{ fontSize: 13.5, fontWeight: 600, overflowWrap: 'anywhere' }}>
              {single.name}
            </Typography>
          </Box>
        ) : (
          <FormControl error={Boolean(errors.choice)}>
            <FormLabel id="consent-choice-label" sx={{ fontSize: 12, mb: 0.75 }}>
              Dienstkonto
            </FormLabel>
            {choices.length > DIRECT_CHOICES && (
              <Box sx={{ mb: 1 }}>
                <ListSearch
                  value={query}
                  onChange={search}
                  placeholder="Name"
                  label="Dienstkonto suchen"
                />
              </Box>
            )}
            <RadioGroup
              ref={groupRef}
              aria-labelledby="consent-choice-label"
              value={chosen}
              onChange={(event) => setChosen(event.target.value)}
              sx={{ maxHeight: 240, overflowY: 'auto', flexWrap: 'nowrap' }}
            >
              {filtered.map((choice) => (
                <FormControlLabel
                  key={choice.id}
                  value={choice.id}
                  control={<Radio size="small" />}
                  label={<Box sx={{ fontSize: 13.5, overflowWrap: 'anywhere' }}>{choice.name}</Box>}
                />
              ))}
            </RadioGroup>
            {filtered.length === 0 && (
              <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
                Keine Treffer.
              </Typography>
            )}
            {errors.choice && <FormHelperText>{errors.choice}</FormHelperText>}
          </FormControl>
        )}
        <Box
          sx={{
            display: 'flex',
            gap: 1.5,
            alignItems: 'flex-start',
            flexWrap: 'wrap',
          }}
        >
          <UntilField
            value={date}
            onChange={setDate}
            maxDays={maxDays}
            error={errors.date}
            inputRef={dateRef}
          />
          <BusyButton
            type="submit"
            variant="outlined"
            busy={saving}
            busyAnnouncement="Wird aufgenommen"
          >
            Aufnehmen
          </BusyButton>
        </Box>
      </Stack>
    </Box>
  )
}

function summaryOf(consents: LibraryServiceAccountConsentResponse[]): string {
  const active = consents.filter((c) => c.status === 'ACTIVE' && c.serviceAccountActive)
  if (active.length === 0) return 'Keines'
  const next = [...active].sort((a, b) => a.expiresAt.localeCompare(b.expiresAt))[0]
  const soon = soonLabel(next.expiresAt)
  const names =
    active.length <= 2
      ? active.map((c) => c.serviceAccountName).join(', ')
      : `${active.length} Dienstkonten`
  return `${names} · nächstes Ende ${formatDate(next.expiresAt)}${soon ? ` (${soon})` : ''}`
}

/**
 * The row „Dienstkonten“ in the Reiter „Freigaben“ of a library: which service accounts may
 * search here, summarized, with „Verwalten“. The dialog puts „Aufnehmen“ on top and lists the
 * consents below, each with its end. A load error disables „Verwalten“ and offers a reload.
 */
export default function LibraryServiceAccountsSection({ libraryId }: { libraryId: string }) {
  const { consents, maxDays, error, reload } = useConsents(libraryId)
  const [managing, setManaging] = useState(false)
  const [changing, setChanging] = useState<LibraryServiceAccountConsentResponse | null>(null)
  const [version, setVersion] = useState(0)
  const listed = useMemo(
    () => (consents ?? []).map((consent) => consent.serviceAccountId),
    [consents],
  )

  function changed() {
    void reload()
    setVersion((value) => value + 1)
  }

  return (
    <ValueRow
      title="Dienstkonten"
      value={
        error ? (
          <LoadError
            message="Die Dienstkonten konnten nicht geladen werden."
            detail={error}
            onRetry={() => void reload()}
          />
        ) : consents === null ? (
          <LoadingState shape="inline" />
        ) : (
          summaryOf(consents)
        )
      }
      action={
        <TextAction
          onClick={() => setManaging(true)}
          context="Dienstkonten"
          disabled={Boolean(error) || consents === null}
        >
          Verwalten
        </TextAction>
      }
    >
      <Dialog
        open={managing}
        onClose={() => setManaging(false)}
        fullWidth
        maxWidth="sm"
        aria-labelledby="consent-manage-title"
      >
        <DialogTitleWithClose id="consent-manage-title" onClose={() => setManaging(false)}>
          Dienstkonten mit Zugriff
        </DialogTitleWithClose>
        <DialogContent>
          <Box sx={{ mb: 3 }}>
            <AddConsentForm
              key={version}
              libraryId={libraryId}
              listed={listed}
              maxDays={maxDays}
              onSaved={(name) => {
                notify(`„${name}“ hat Zugriff auf diese Bibliothek.`, 'success')
                changed()
              }}
            />
          </Box>
          {consents && consents.length > 0 ? (
            <ConsentRows
              libraryId={libraryId}
              consents={consents}
              onChangeDate={setChanging}
              onChanged={changed}
            />
          ) : (
            <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
              Kein Dienstkonto hat Zugriff auf diese Bibliothek.
            </Typography>
          )}
        </DialogContent>
      </Dialog>
      <ChangeDateDialog
        libraryId={libraryId}
        consent={changing}
        maxDays={maxDays}
        onClose={() => setChanging(null)}
        onSaved={() => {
          setChanging(null)
          changed()
        }}
      />
    </ValueRow>
  )
}
