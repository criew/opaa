import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Divider from '@mui/material/Divider'
import FormControlLabel from '@mui/material/FormControlLabel'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { successionAwareMessage } from '../succession/successionConflict'
import SectionHead from '../SectionHead'
import type { LibraryExternalAccessResponse } from '../../types/api'
import { getLibrary, updateLibraryExternalAccess } from '../../services/api'

interface LibraryExternalAccessSectionProps {
  libraryId: string
}

/**
 * Bewusst ohne Ende angegebene Uhrzeit: das Datum wird in der Zeitzone des Aufrufers auf das Ende
 * des gewählten Kalendertags gelegt, damit die Freigabe an dem Tag endet, den die Person gewählt
 * hat - dieselbe Regel wie bei der Befristung eines Grants.
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

/** Nie gesetzt · gültig bis · zurückgenommen · erloschen · ausgesetzt (letzteres kommt mit #797). */
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
      return 'Diese Bibliothek war noch nie für Fremdzugänge freigegeben.'
  }
}

/**
 * Ein Tag weniger als die Obergrenze: `toExpiresAt` legt den gewählten Tag auf sein lokales Ende,
 * sodass der gesendete Zeitpunkt bis zu 24 Stunden hinter dem gewählten Datum liegt. Ohne den Abzug
 * böte das Feld genau den Wert als zulässig an, den das Backend mit „gilt höchstens N Tage"
 * abweist.
 */
function latestAdmissibleDate(maxReleaseDays: number): string {
  const latest = new Date()
  latest.setDate(latest.getDate() + maxReleaseDays - 1)
  return toDateInput(latest.toISOString())
}

/**
 * Die Freigabe einer Bibliothek für Fremdzugänge (#1731) im Zugriffsbereich der Bibliothek:
 * Schalter, Pflicht-Ablaufdatum und die Anzahl der Zugangstokens, die die Bibliothek enthalten -
 * eine Zahl, keine Namen. Sichtbar für alle, die an der Bibliothek Rechte vergeben dürfen; unterhalb
 * dieser Rolle liefert das Backend das Feld gar nicht erst.
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
      {/* Der Trenner gehört dieser Sektion, nicht dem Dialog: unterhalb der Verwalter-Rolle
          liefert das Backend die Freigabe gar nicht, und ein Trenner ohne folgenden Abschnitt
          wäre eine Linie, die nichts trennt. */}
      <Divider sx={{ my: 1 }} />
      <SectionHead component="h3">Fremdzugänge</SectionHead>
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
            slotProps={{ input: { 'aria-label': 'Über Fremdzugänge nutzbar' } }}
            onChange={(event) => void apply(event.target.checked)}
          />
        }
        label="Über Fremdzugänge nutzbar"
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
