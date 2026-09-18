import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import FormControl from '@mui/material/FormControl'
import FormControlLabel from '@mui/material/FormControlLabel'
import FormGroup from '@mui/material/FormGroup'
import FormHelperText from '@mui/material/FormHelperText'
import FormLabel from '@mui/material/FormLabel'
import Skeleton from '@mui/material/Skeleton'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type {
  CreatedExternalAccessTokenResponse,
  EligibleExternalAccessLibraryResponse,
} from '../../types/api'
import {
  createExternalAccessToken,
  listEligibleExternalAccessLibraries,
} from '../../services/externalAccessApi'
import { radius } from '../../theme/tokens'
import {
  DISCLOSURE_HINT,
  NO_LIBRARIES_HINT,
  SELECTION_IS_FINAL_HINT,
  expiryInstantOf,
  formatDate,
  maxExpiryDate,
  releaseEndsBeforeExpiry,
  toDateInputValue,
} from './tokenLabels'

interface CreateExternalAccessTokenDialogProps {
  /** Die Höchstlaufzeit der Installation; sie ist zugleich die Vorgabe des Ablaufdatums. */
  tokenMaxLifetimeDays: number
  onClose: () => void
  onCreated: (created: CreatedExternalAccessTokenResponse) => void
}

interface FieldErrors {
  name?: string
  libraryIds?: string
  expiresAt?: string
}

/**
 * Der Anlegedialog eines Zugangstokens (#1719).
 *
 * Die Aufklärung steht als Fließtext im Formular und nicht in einer Fußnote: Sie ist der Grund,
 * aus dem dieser Dialog eine bewusste Handlung ist und kein Knopf - was hier erteilt wird, läuft
 * anschließend durch ein Werkzeug, über dessen Protokollierung OPAA nichts zusagen kann.
 *
 * Die Auswahl kommt aus `eligible-libraries` und ist damit genau die Menge, die die Ausstellung
 * annimmt - lesbar und freigegeben. Alles, was der Dialog anbietet, ist ausstellbar; eine
 * Bibliothek mehr anzubieten hieße, die Person in eine Abweisung laufen zu lassen.
 */
export default function CreateExternalAccessTokenDialog({
  tokenMaxLifetimeDays,
  onClose,
  onCreated,
}: CreateExternalAccessTokenDialogProps) {
  const [libraries, setLibraries] = useState<EligibleExternalAccessLibraryResponse[] | null>(null)
  const [name, setName] = useState('')
  const [selected, setSelected] = useState<string[]>([])
  // Der Entwurf lebt nur, solange der Dialog montiert ist - der Aufrufer montiert ihn je Vorgang
  // neu. Ein Zurücksetzen im Effekt wäre derselbe Zustand, nur einen Renderdurchlauf später.
  const [expiresOn, setExpiresOn] = useState(() =>
    toDateInputValue(maxExpiryDate(tokenMaxLifetimeDays)),
  )
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({})
  const [error, setError] = useState<string | null>(null)
  const [isSaving, setIsSaving] = useState(false)

  const latest = maxExpiryDate(tokenMaxLifetimeDays)
  const latestValue = toDateInputValue(latest)
  const earliestValue = toDateInputValue(new Date())

  useEffect(() => {
    let active = true
    listEligibleExternalAccessLibraries()
      .then((loaded) => {
        if (active) setLibraries(loaded)
      })
      .catch((err: unknown) => {
        if (!active) return
        setLibraries([])
        setError(
          err instanceof Error ? err.message : 'Die Bibliotheken konnten nicht geladen werden.',
        )
      })
    return () => {
      active = false
    }
  }, [])

  function toggle(libraryId: string) {
    setSelected((current) =>
      current.includes(libraryId)
        ? current.filter((id) => id !== libraryId)
        : [...current, libraryId],
    )
  }

  function validate(): FieldErrors {
    const errors: FieldErrors = {}
    if (name.trim() === '') {
      errors.name =
        'Bitte geben Sie einen Namen an - er ist später das Einzige, woran Sie dieses Token erkennen.'
    }
    if (selected.length === 0) {
      errors.libraryIds = 'Bitte wählen Sie mindestens eine Bibliothek aus.'
    }
    if (expiresOn === '') {
      errors.expiresAt = 'Bitte geben Sie ein Ablaufdatum an - ein Token ohne Ablauf gibt es nicht.'
    } else if (expiresOn > latestValue) {
      errors.expiresAt = `Das Ablaufdatum liegt höchstens ${tokenMaxLifetimeDays} Tage in der Zukunft, also spätestens am ${latest.toLocaleDateString('de-DE')}.`
    } else if (expiresOn <= earliestValue) {
      errors.expiresAt = 'Das Ablaufdatum muss in der Zukunft liegen.'
    }
    return errors
  }

  async function handleSubmit() {
    const errors = validate()
    setFieldErrors(errors)
    if (Object.keys(errors).length > 0) return
    setError(null)
    setIsSaving(true)
    try {
      const created = await createExternalAccessToken({
        name: name.trim(),
        libraryIds: selected,
        expiresAt: expiryInstantOf(expiresOn, latest),
      })
      onCreated(created)
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Das Token konnte nicht erzeugt werden.')
    } finally {
      setIsSaving(false)
    }
  }

  const hasLibraries = libraries !== null && libraries.length > 0
  const endingReleases =
    expiresOn === ''
      ? []
      : releaseEndsBeforeExpiry(libraries ?? [], selected, expiryInstantOf(expiresOn, latest))

  return (
    <Dialog open fullWidth maxWidth="sm" onClose={onClose} aria-labelledby="create-token-title">
      <DialogTitle id="create-token-title">Token erzeugen</DialogTitle>
      <DialogContent>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}
        <Stack spacing={3} sx={{ mt: 1 }}>
          <TextField
            label="Name / Zweck"
            value={name}
            onChange={(e) => setName(e.target.value)}
            error={Boolean(fieldErrors.name)}
            helperText={
              fieldErrors.name ??
              'Zum Beispiel „Claude Code auf dem Dienstrechner“ - nicht „Token 3“.'
            }
            slotProps={{ htmlInput: { maxLength: 120 } }}
            fullWidth
          />

          <FormControl component="fieldset" error={Boolean(fieldErrors.libraryIds)}>
            <FormLabel component="legend" sx={{ fontSize: 13.5 }}>
              Bibliotheken
            </FormLabel>
            {libraries === null ? (
              <Skeleton variant="rounded" height={96} sx={{ mt: 1 }} />
            ) : hasLibraries ? (
              <>
                <FormGroup sx={{ mt: 0.5 }}>
                  {libraries.map((library) => (
                    <FormControlLabel
                      key={library.id}
                      control={
                        <Checkbox
                          checked={selected.includes(library.id)}
                          onChange={() => toggle(library.id)}
                        />
                      }
                      label={
                        <>
                          {library.name}
                          {/* Die Freigabe der Bibliothek endet unabhängig vom Token; läuft sie
                              früher aus, verliert das Token sie vorher. */}
                          <Typography
                            component="span"
                            sx={{ fontSize: 12, color: 'text.secondary', ml: 1 }}
                          >
                            Freigabe bis {formatDate(library.releaseExpiresAt)}
                          </Typography>
                        </>
                      }
                    />
                  ))}
                </FormGroup>
                <FormHelperText>{fieldErrors.libraryIds ?? SELECTION_IS_FINAL_HINT}</FormHelperText>
              </>
            ) : (
              <Alert severity="info" sx={{ mt: 1 }}>
                {NO_LIBRARIES_HINT}
              </Alert>
            )}
          </FormControl>

          <TextField
            label="Läuft ab"
            type="date"
            value={expiresOn}
            onChange={(e) => setExpiresOn(e.target.value)}
            error={Boolean(fieldErrors.expiresAt)}
            helperText={
              fieldErrors.expiresAt ??
              `Höchstens ${tokenMaxLifetimeDays} Tage, also spätestens am ${latest.toLocaleDateString('de-DE')}.`
            }
            slotProps={{
              inputLabel: { shrink: true },
              htmlInput: { min: earliestValue, max: latestValue },
            }}
            fullWidth
          />

          {endingReleases.length > 0 && (
            <Alert severity="info">
              Die Freigabe von {endingReleases.join(', ')} endet vor diesem Ablaufdatum. Ab dann
              wirkt die Bibliothek in diesem Token nicht mehr und lebt auch bei einer erneuten
              Freigabe nicht wieder auf - dafür wäre ein neues Token nötig.
            </Alert>
          )}

          <Box>
            <Typography sx={{ fontSize: 13.5, fontWeight: 500 }}>Rechte</Typography>
            <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
              Nur lesen und suchen. Der Umfang ist fest und nicht wählbar.
            </Typography>
          </Box>

          <Box
            sx={{
              p: 1.5,
              border: 1,
              borderColor: 'divider',
              borderRadius: `${radius.sm}px`,
            }}
          >
            <Typography sx={{ fontSize: 13 }}>{DISCLOSURE_HINT}</Typography>
          </Box>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Abbrechen</Button>
        <Button variant="contained" onClick={() => void handleSubmit()} disabled={isSaving}>
          Erzeugen
        </Button>
      </DialogActions>
    </Dialog>
  )
}
