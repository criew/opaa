import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { successionAwareMessage } from '@/features/succession'
import { confirmAction } from '@/shared/stores/confirmStore'
import { TextAction } from '@/shared/ui/DetailSection'
import ValueRow from '@/shared/ui/ValueRow'
import type { LibraryExternalAccessResponse } from '@/shared/api/types'
import { getLibrary } from '../api/libraryApi'
import { updateLibraryExternalAccess } from '@/features/external-access'
import { formatDate } from '@/shared/utils/formatDate'
import FormDialog from '@/shared/ui/dialog/FormDialog'
import { fieldWidth } from '@/shared/theme/tokens'

interface LibraryExternalAccessSectionProps {
  libraryId: string
}

/**
 * Deliberately without a time of day: the date is placed at the end of the chosen calendar day
 * in the caller's time zone, so that the release ends on the day the person chose -
 * the same rule as for the time limit of a grant.
 */
function toExpiresAt(dateInput: string): string | null {
  if (!dateInput) return null
  return new Date(`${dateInput}T23:59:59.999`).toISOString()
}

function toDateInput(value: string): string {
  const date = new Date(value)
  const month = `${date.getMonth() + 1}`.padStart(2, '0')
  const day = `${date.getDate()}`.padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
}

function tokenLine(count: number): string {
  if (count === 0) return 'noch in keinem Zugangstoken'
  return count === 1 ? 'in 1 Zugangstoken' : `in ${count} Zugangstokens`
}

/** The state as the row's value: short, with the token count only where a release is in force. */
function stateValue(access: LibraryExternalAccessResponse): string {
  switch (access.state) {
    case 'ACTIVE':
      return `Freigegeben bis ${formatDate(access.expiresAt)} · ${tokenLine(access.tokenCount)}`
    case 'WITHDRAWN':
      return `Nicht freigegeben · am ${formatDate(access.setAt)} zurückgenommen`
    case 'EXPIRED':
      return `Nicht freigegeben · am ${formatDate(access.expiresAt)} erloschen`
    case 'SUSPENDED':
      return 'Freigabe ausgesetzt'
    default:
      return 'Nicht freigegeben'
  }
}

/**
 * One day less than the upper bound: `toExpiresAt` places the chosen day at its local end,
 * so the point in time sent lies up to 24 hours after the chosen date. Without the deduction
 * the field would offer as admissible exactly the value the backend rejects with
 * „gilt höchstens N Tage".
 */
function latestAdmissibleDate(maxReleaseDays: number): string {
  const latest = new Date()
  latest.setDate(latest.getDate() + maxReleaseDays - 1)
  return toDateInput(latest.toISOString())
}

/**
 * A library's release for external access as one row of the Reiter „Freigaben" — state on the
 * left, „Freigeben" resp. „Ändern" at the right edge (guidelines 5.1). The change pops up in a
 * dialog: the expiry date (never pre-filled), the number of access tokens that hold the library
 * (a number, no names), and „Freigabe widerrufen" with a confirmation. Visible to everyone who may
 * grant rights on the library; below this role the backend omits the field entirely.
 */
export default function LibraryExternalAccessSection({
  libraryId,
}: LibraryExternalAccessSectionProps) {
  const [access, setAccess] = useState<LibraryExternalAccessResponse | null>(null)
  const [open, setOpen] = useState(false)
  const [dateInput, setDateInput] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [dialogError, setDialogError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    void getLibrary(libraryId)
      .then((library) => {
        setAccess(library.externalAccess ?? null)
        setError(null)
      })
      .catch((err: unknown) =>
        setError(err instanceof Error ? err.message : 'Die Freigabe konnte nicht geladen werden'),
      )
  }, [libraryId])

  if (!access) {
    // a failed load is named, never swallowed; a missing field (below the manager role) shows nothing
    return error ? <Alert severity="error">{error}</Alert> : null
  }

  const released = access.state === 'ACTIVE'

  function openDialog() {
    // „Ändern" starts from the running expiry; „Freigeben" starts empty - a release is never
    // pre-dated on the person's behalf.
    setDateInput(released && access?.expiresAt ? toDateInput(access.expiresAt) : '')
    setDialogError(null)
    setOpen(true)
  }

  async function save() {
    if (!dateInput) {
      setDialogError('Bitte ein Ablaufdatum angeben – eine Freigabe ohne Ende ist nicht möglich.')
      return
    }
    setDialogError(null)
    setSaving(true)
    try {
      const updated = await updateLibraryExternalAccess(libraryId, true, toExpiresAt(dateInput))
      setAccess(updated)
      setOpen(false)
    } catch (err) {
      setDialogError(successionAwareMessage(err, 'Die Freigabe konnte nicht geändert werden'))
    } finally {
      setSaving(false)
    }
  }

  async function end() {
    const confirmed = await confirmAction({
      question: 'Freigabe für externe KI-Werkzeuge widerrufen?',
      consequence:
        'Zugangstokens, die diese Bibliothek enthalten, finden danach nichts mehr darin. Eine erneute Freigabe ist jederzeit möglich.',
      confirmLabel: 'Widerrufen',
      tone: 'danger',
    })
    if (!confirmed) return
    setDialogError(null)
    setSaving(true)
    try {
      const updated = await updateLibraryExternalAccess(libraryId, false, null)
      setAccess(updated)
      setOpen(false)
    } catch (err) {
      setDialogError(successionAwareMessage(err, 'Die Freigabe konnte nicht geändert werden'))
    } finally {
      setSaving(false)
    }
  }

  return (
    <ValueRow
      title="Externer Zugang"
      value={stateValue(access)}
      action={
        <TextAction onClick={openDialog} context="Externer Zugang">
          {released ? 'Ändern' : 'Freigeben'}
        </TextAction>
      }
    >
      <FormDialog
        open={open}
        title="Externer Zugang"
        submitLabel={released ? 'Speichern' : 'Freigeben'}
        onSubmit={() => void save()}
        onClose={() => setOpen(false)}
        busy={saving}
        busyAnnouncement="Die Freigabe wird gespeichert"
        secondaryAction={
          released && (
            <Button variant="outlined" color="error" disabled={saving} onClick={() => void end()}>
              Freigabe widerrufen
            </Button>
          )
        }
      >
        <Stack spacing={2} sx={{ mt: 1 }}>
          {dialogError && <Alert severity="error">{dialogError}</Alert>}
          <TextField
            label="Freigabe bis"
            type="date"
            value={dateInput}
            disabled={saving}
            onChange={(event) => setDateInput(event.target.value)}
            slotProps={{
              inputLabel: { shrink: true },
              htmlInput: { max: latestAdmissibleDate(access.maxReleaseDays) },
            }}
            size="small"
            sx={{ maxWidth: fieldWidth.short }}
            helperText={`höchstens ${access.maxReleaseDays} Tage`}
          />
          <Typography variant="caption" sx={{ color: 'text.secondary' }}>
            Personen mit Lesezugriff können diese Bibliothek in einem eigenen Zugangstoken auswählen
            und aus externen Werkzeugen darin suchen.
          </Typography>
          <Typography variant="caption" sx={{ color: 'text.secondary' }}>
            Nach Ablauf erlischt die Freigabe; die Erneuerung ist eine bewusste Entscheidung der
            Bibliotheksverantwortung.
          </Typography>
          {released && (
            <Typography variant="caption" sx={{ color: 'text.secondary' }}>
              Derzeit {tokenLine(access.tokenCount)} enthalten.
            </Typography>
          )}
        </Stack>
      </FormDialog>
    </ValueRow>
  )
}
