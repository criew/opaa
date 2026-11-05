import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import Alert from '@mui/material/Alert'
import Autocomplete from '@mui/material/Autocomplete'
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
import Skeleton from '@mui/material/Skeleton'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type {
  LibraryServiceAccountConsentResponse,
  ServiceAccountChoiceResponse,
} from '@/shared/api/types'
import { confirmAction } from '@/shared/stores/confirmStore'
import { notify } from '@/shared/stores/notificationStore'
import { currentUiLabVariant } from '@/shared/uiLabVariant'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import DetailSection, { TextAction } from '@/shared/ui/DetailSection'
import DialogTitleWithClose from '@/shared/ui/DialogTitleWithClose'
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
import { Ending } from './ServiceAccountParts'
import { REMOVE_CONSENT_CONSEQUENCE, formatDate, soonLabel } from './serviceAccountLabels'

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
            err instanceof Error ? err.message : 'Die Fachverfahren konnten nicht geladen werden.',
          ),
        ),
    [libraryId],
  )
  useEffect(() => {
    void reload()
  }, [reload])
  return { consents, maxDays, error, reload }
}

function useChoices(libraryId: string, active: boolean) {
  const [choices, setChoices] = useState<ServiceAccountChoiceResponse[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    if (!active) return
    void listServiceAccountChoices(libraryId)
      .then((loaded) => {
        setChoices(loaded)
        setError(null)
      })
      .catch((err: unknown) =>
        setError(err instanceof Error ? err.message : 'Die Auswahl konnte nicht geladen werden.'),
      )
  }, [libraryId, active])
  return { choices, error }
}

/** The state of one consent: „bis <Datum>“, expired, or the account deactivated. */
function ConsentState({ consent }: { consent: LibraryServiceAccountConsentResponse }) {
  if (!consent.serviceAccountActive) {
    return <StateDot tone="neutral" label="Dienstkonto deaktiviert" />
  }
  if (consent.status === 'EXPIRED') {
    return <StateDot tone="neutral" label={`Abgelaufen am ${formatDate(consent.expiresAt)}`} />
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
      aria-label="Fachverfahren mit Zugriff"
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
  compact = false,
}: {
  value: string
  onChange: (value: string) => void
  maxDays: number
  error?: string
  inputRef?: React.Ref<HTMLInputElement>
  compact?: boolean
}) {
  return (
    <TextField
      label="Zugriff bis"
      type="date"
      value={value}
      onChange={(event) => onChange(event.target.value)}
      inputRef={inputRef}
      error={Boolean(error)}
      helperText={error ?? (compact ? undefined : `höchstens ${maxDays} Tage`)}
      size="small"
      sx={{ width: compact ? '100%' : 220 }}
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
  return <ChangeDateForm key={props.consent.serviceAccountId} {...props} />
}

function ChangeDateForm({ libraryId, consent, maxDays, onClose, onSaved }: ChangeDateDialogProps) {
  const [date, setDate] = useState(
    consent?.status === 'ACTIVE' ? toDateInputValue(new Date(consent.expiresAt)) : '',
  )
  const [error, setError] = useState<string | undefined>()
  const [saving, setSaving] = useState(false)
  const ref = useRef<HTMLInputElement>(null)
  if (!consent) return null

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!consent) return
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

/** Variant A: „Aufnehmen“ opens a dialog with the active accounts as a radio list and the end. */
interface AddConsentDialogProps {
  libraryId: string
  open: boolean
  maxDays: number
  onClose: () => void
  onSaved: (name: string) => void
}

function AddConsentDialog(props: AddConsentDialogProps) {
  // a fresh form per opening, with the choices of that moment
  if (!props.open) return null
  return <AddConsentForm {...props} />
}

function AddConsentForm({ libraryId, open, maxDays, onClose, onSaved }: AddConsentDialogProps) {
  const { choices, error: loadError } = useChoices(libraryId, open)
  const [query, setQuery] = useState('')
  const [chosen, setChosen] = useState('')
  const [date, setDate] = useState('')
  const [errors, setErrors] = useState<{ choice?: string; date?: string; form?: string }>({})
  const [saving, setSaving] = useState(false)
  const dateRef = useRef<HTMLInputElement>(null)
  const groupRef = useRef<HTMLDivElement>(null)

  const single = choices?.length === 1 ? choices[0] : null
  const filtered = useMemo(() => {
    const needle = query.trim().toLocaleLowerCase('de')
    return (choices ?? []).filter((choice) => choice.name.toLocaleLowerCase('de').includes(needle))
  }, [choices, query])

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
    try {
      await setLibraryServiceAccountConsent(libraryId, id, toExpiresAt(date))
      onSaved((choices ?? []).find((choice) => choice.id === id)?.name ?? '')
    } catch (err) {
      setErrors({ form: err instanceof Error ? err.message : 'Das Aufnehmen ist fehlgeschlagen.' })
    } finally {
      setSaving(false)
    }
  }

  return (
    <Dialog
      open={open}
      onClose={() => !saving && onClose()}
      fullWidth
      maxWidth="sm"
      aria-labelledby="consent-add-title"
    >
      <form noValidate onSubmit={(event) => void submit(event)}>
        <DialogTitle id="consent-add-title">Fachverfahren aufnehmen</DialogTitle>
        <DialogContent>
          <Stack spacing={2.5} sx={{ mt: 1 }}>
            {(errors.form || loadError) && (
              <Alert severity="error">{errors.form ?? loadError}</Alert>
            )}
            {choices === null && !loadError ? (
              <Skeleton variant="rounded" height={96} />
            ) : choices && choices.length === 0 ? (
              <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
                Kein weiteres aktives Dienstkonto. Dienstkonten legt die Systemverwaltung an.
              </Typography>
            ) : single ? (
              <Box>
                <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>Dienstkonto</Typography>
                <Typography sx={{ fontSize: 13.5, fontWeight: 600 }}>{single.name}</Typography>
              </Box>
            ) : (
              <FormControl error={Boolean(errors.choice)}>
                <FormLabel id="consent-choice-label" sx={{ fontSize: 12, mb: 0.75 }}>
                  Dienstkonto
                </FormLabel>
                {choices && choices.length > DIRECT_CHOICES && (
                  <TextField
                    size="small"
                    placeholder="Dienstkonto suchen"
                    value={query}
                    onChange={(event) => setQuery(event.target.value)}
                    slotProps={{ htmlInput: { 'aria-label': 'Dienstkonto suchen' } }}
                    sx={{ mb: 1 }}
                  />
                )}
                <RadioGroup
                  ref={groupRef}
                  aria-labelledby="consent-choice-label"
                  value={chosen}
                  onChange={(event) => setChosen(event.target.value)}
                  sx={{ maxHeight: 280, overflowY: 'auto', flexWrap: 'nowrap' }}
                >
                  {filtered.map((choice) => (
                    <FormControlLabel
                      key={choice.id}
                      value={choice.id}
                      control={<Radio size="small" />}
                      label={
                        <Box sx={{ fontSize: 13.5, overflowWrap: 'anywhere' }}>{choice.name}</Box>
                      }
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
            {choices && choices.length > 0 && (
              <UntilField
                value={date}
                onChange={setDate}
                maxDays={maxDays}
                error={errors.date}
                inputRef={dateRef}
              />
            )}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button variant="text" onClick={onClose} disabled={saving}>
            Abbrechen
          </Button>
          {choices && choices.length > 0 && (
            <BusyButton
              type="submit"
              variant="contained"
              busy={saving}
              busyAnnouncement="Wird aufgenommen"
            >
              Aufnehmen
            </BusyButton>
          )}
        </DialogActions>
      </form>
    </Dialog>
  )
}

/** Variant B and C: account, end and „Aufnehmen“ in one row under the list. */
function AddConsentRow({
  libraryId,
  maxDays,
  onSaved,
}: {
  libraryId: string
  maxDays: number
  onSaved: (name: string) => void
}) {
  // the parent remounts the row after every change, so the choices are always fresh
  const { choices } = useChoices(libraryId, true)
  const [chosen, setChosen] = useState<ServiceAccountChoiceResponse | null>(null)
  const [date, setDate] = useState('')
  const [errors, setErrors] = useState<{ choice?: string; date?: string; form?: string }>({})
  const [saving, setSaving] = useState(false)
  const choiceRef = useRef<HTMLInputElement>(null)
  const dateRef = useRef<HTMLInputElement>(null)

  async function submit(event: FormEvent) {
    event.preventDefault()
    const found: typeof errors = {}
    if (!chosen) found.choice = 'Bitte ein Dienstkonto auswählen.'
    if (!date) found.date = 'Bitte ein Datum angeben.'
    setErrors(found)
    if (found.choice) return choiceRef.current?.focus()
    if (found.date) return dateRef.current?.focus()
    setSaving(true)
    try {
      await setLibraryServiceAccountConsent(libraryId, chosen!.id, toExpiresAt(date))
      const name = chosen!.name
      onSaved(name)
    } catch (err) {
      setErrors({ form: err instanceof Error ? err.message : 'Das Aufnehmen ist fehlgeschlagen.' })
    } finally {
      setSaving(false)
    }
  }

  if (choices && choices.length === 0) {
    return (
      <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mt: 1.5 }}>
        Kein weiteres aktives Dienstkonto zur Auswahl.
      </Typography>
    )
  }

  return (
    <Box component="form" noValidate onSubmit={(event) => void submit(event)} sx={{ mt: 2 }}>
      {errors.form && (
        <Alert severity="error" sx={{ mb: 1.5 }}>
          {errors.form}
        </Alert>
      )}
      <Box
        sx={{
          display: 'grid',
          gap: 1.5,
          alignItems: 'start',
          gridTemplateColumns: { xs: 'minmax(0, 1fr)', sm: 'minmax(0, 1fr) 180px auto' },
        }}
      >
        <Autocomplete
          options={choices ?? []}
          loading={choices === null}
          value={chosen}
          onChange={(_, value) => setChosen(value)}
          getOptionLabel={(option) => option.name}
          isOptionEqualToValue={(a, b) => a.id === b.id}
          noOptionsText="Keine Treffer"
          loadingText="Wird geladen …"
          renderInput={(params) => (
            <TextField
              {...params}
              inputRef={choiceRef}
              size="small"
              label="Dienstkonto"
              placeholder="Dienstkonto suchen …"
              error={Boolean(errors.choice)}
              helperText={errors.choice}
            />
          )}
        />
        <UntilField
          value={date}
          onChange={setDate}
          maxDays={maxDays}
          error={errors.date}
          inputRef={dateRef}
          compact
        />
        <Box sx={{ minHeight: 40, display: 'flex', alignItems: 'center' }}>
          <BusyButton
            type="submit"
            variant="outlined"
            busy={saving}
            busyAnnouncement="Wird aufgenommen"
          >
            Aufnehmen
          </BusyButton>
        </Box>
      </Box>
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
      : `${active.length} Fachverfahren`
  return `${names} · nächstes Ende ${formatDate(next.expiresAt)}${soon ? ` (${soon})` : ''}`
}

/**
 * „Fachverfahren mit Zugriff“ in the Reiter „Freigaben“ of a library (epic #3587): which service
 * accounts may search here, each with an end. `placement` lets the tab put the section where the
 * active UI lab variant wants it; the other placement renders nothing.
 */
export default function LibraryServiceAccountsSection({
  libraryId,
  placement,
}: {
  libraryId: string
  placement: 'section' | 'row'
}) {
  const variant = currentUiLabVariant()
  const wanted = variant === 'b' ? 'row' : variant === 'a' || variant === 'c' ? 'section' : null
  if (wanted !== placement) return null
  return <Section libraryId={libraryId} variant={variant!} />
}

function Section({ libraryId, variant }: { libraryId: string; variant: string }) {
  const { consents, maxDays, error, reload } = useConsents(libraryId)
  const [adding, setAdding] = useState(false)
  const [managing, setManaging] = useState(false)
  const [changing, setChanging] = useState<LibraryServiceAccountConsentResponse | null>(null)
  const [version, setVersion] = useState(0)

  function changed() {
    void reload()
    setVersion((value) => value + 1)
  }

  const body = error ? (
    <Alert
      severity="error"
      action={
        <Button color="inherit" size="small" onClick={() => void reload()}>
          Erneut laden
        </Button>
      }
    >
      {error}
    </Alert>
  ) : consents === null ? (
    <Skeleton variant="rounded" height={56} />
  ) : consents.length === 0 ? (
    <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
      Kein Fachverfahren hat Zugriff auf diese Bibliothek.
    </Typography>
  ) : (
    <ConsentRows
      libraryId={libraryId}
      consents={consents}
      onChangeDate={setChanging}
      onChanged={changed}
    />
  )

  const dateDialog = (
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
  )

  if (variant === 'b') {
    return (
      <ValueRow
        title="Fachverfahren"
        value={error ? 'nicht geladen' : consents === null ? '…' : summaryOf(consents)}
        action={
          <TextAction onClick={() => setManaging(true)} context="Fachverfahren">
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
            Fachverfahren mit Zugriff
          </DialogTitleWithClose>
          <DialogContent>
            {body}
            {!error && (
              <AddConsentRow
                libraryId={libraryId}
                maxDays={maxDays}
                onSaved={() => changed()}
                key={version}
              />
            )}
          </DialogContent>
        </Dialog>
        {dateDialog}
      </ValueRow>
    )
  }

  if (variant === 'c') {
    return (
      <DetailSection title="Fachverfahren mit Zugriff">
        {body}
        {!error && (
          <AddConsentRow
            libraryId={libraryId}
            maxDays={maxDays}
            onSaved={() => changed()}
            key={version}
          />
        )}
        {dateDialog}
      </DetailSection>
    )
  }

  return (
    <DetailSection
      title="Fachverfahren mit Zugriff"
      action={<TextAction onClick={() => setAdding(true)}>Aufnehmen</TextAction>}
    >
      {body}
      <AddConsentDialog
        libraryId={libraryId}
        open={adding}
        maxDays={maxDays}
        onClose={() => setAdding(false)}
        onSaved={() => {
          setAdding(false)
          changed()
        }}
      />
      {dateDialog}
    </DetailSection>
  )
}
