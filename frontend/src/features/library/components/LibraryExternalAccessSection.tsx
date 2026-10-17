import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Divider from '@mui/material/Divider'
import FormControlLabel from '@mui/material/FormControlLabel'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { successionAwareMessage } from '@/features/succession'
import SectionHead from '@/shared/ui/SectionHead'
import type { LibraryExternalAccessResponse } from '@/shared/api/types'
import { getLibrary } from '../api/libraryApi'
import { updateLibraryExternalAccess } from '@/features/external-access'

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

function formatDate(value: string | null | undefined): string {
  if (!value) return '—'
  return new Date(value).toLocaleDateString('de-DE')
}

/** Never set · valid until · withdrawn · expired · suspended. */
function stateLine(access: LibraryExternalAccessResponse): string {
  switch (access.state) {
    case 'ACTIVE':
      return `Freigegeben bis ${formatDate(access.expiresAt)}.`
    case 'WITHDRAWN':
      return `Die Freigabe wurde am ${formatDate(access.setAt)} zurückgenommen.`
    case 'EXPIRED':
      return `Die Freigabe ist am ${formatDate(access.expiresAt)} erloschen.`
    case 'SUSPENDED':
      return 'Die Freigabe ist ausgesetzt und wirkt derzeit nicht.'
    default:
      return 'Diese Bibliothek war noch nie für externe KI-Werkzeuge freigegeben.'
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
 * A library's release for external access in the library's access area:
 * switch, mandatory expiry date and the number of access tokens that contain the library -
 * a number, no names. Visible to everyone who may grant rights on the library; below
 * this role the backend does not deliver the field at all.
 */
export default function LibraryExternalAccessSection({
  libraryId,
}: LibraryExternalAccessSectionProps) {
  const [access, setAccess] = useState<LibraryExternalAccessResponse | null>(null)
  const [dateInput, setDateInput] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    void getLibrary(libraryId)
      .then((library) => {
        setAccess(library.externalAccess ?? null)
        setDateInput(
          library.externalAccess?.expiresAt ? toDateInput(library.externalAccess.expiresAt) : '',
        )
        setError(null)
      })
      .catch((err: unknown) =>
        setError(err instanceof Error ? err.message : 'Die Freigabe konnte nicht geladen werden'),
      )
  }, [libraryId])

  async function apply(enabled: boolean) {
    setError(null)
    if (enabled && !dateInput) {
      setError('Bitte ein Ablaufdatum angeben - eine Freigabe ohne Ende ist nicht möglich.')
      return
    }
    setSaving(true)
    try {
      const updated = await updateLibraryExternalAccess(
        libraryId,
        enabled,
        enabled ? toExpiresAt(dateInput) : null,
      )
      setAccess(updated)
      setDateInput(
        updated.expiresAt && updated.state === 'ACTIVE' ? toDateInput(updated.expiresAt) : '',
      )
    } catch (err) {
      setError(successionAwareMessage(err, 'Die Freigabe konnte nicht geändert werden'))
    } finally {
      setSaving(false)
    }
  }

  if (!access) {
    return null
  }

  const released = access.state === 'ACTIVE'
  return (
    <Stack spacing={1}>
      {/* The divider belongs to this section, not to the dialog: below the manager role
         the backend does not deliver the release at all, and a divider without a following section
         would be a line that separates nothing. */}
      <Divider sx={{ my: 1 }} />
      <SectionHead component="h3">Externe KI-Werkzeuge</SectionHead>
      {error && (
        <Alert severity="error" onClose={() => setError(null)}>
          {error}
        </Alert>
      )}
      <FormControlLabel
        control={
          <Switch
            checked={released}
            disabled={saving}
            slotProps={{ input: { 'aria-label': 'Für externe KI-Werkzeuge nutzbar' } }}
            onChange={(event) => void apply(event.target.checked)}
          />
        }
        label="Für externe KI-Werkzeuge nutzbar"
      />
      <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
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
          sx={{ maxWidth: 220 }}
        />
        {released && (
          <Button size="small" disabled={saving} onClick={() => void apply(true)}>
            Ablaufdatum übernehmen
          </Button>
        )}
        <Typography variant="caption" sx={{ color: 'text.secondary' }}>
          höchstens {access.maxReleaseDays} Tage
        </Typography>
      </Stack>
      <Typography variant="caption" sx={{ color: 'text.secondary' }}>
        Personen mit Lesezugriff können diese Bibliothek in einem eigenen Zugangstoken auswählen und
        aus externen Werkzeugen darin suchen.
      </Typography>
      <Typography variant="caption" sx={{ color: 'text.secondary' }}>
        Derzeit in {access.tokenCount} Zugangstokens enthalten.
      </Typography>
      <Typography variant="caption" sx={{ color: 'text.secondary' }}>
        {stateLine(access)} Nach Ablauf erlischt die Freigabe; die Erneuerung ist eine bewusste
        Entscheidung der Bibliotheksverantwortung.
      </Typography>
    </Stack>
  )
}
