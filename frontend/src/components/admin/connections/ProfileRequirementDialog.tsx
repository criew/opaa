import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import FormControlLabel from '@mui/material/FormControlLabel'
import List from '@mui/material/List'
import ListItem from '@mui/material/ListItem'
import Radio from '@mui/material/Radio'
import RadioGroup from '@mui/material/RadioGroup'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import type {
  ConnectorProfileRequirementResponse,
  ConnectorTypeStateResponse,
  OwnAddressLibrary,
  OwnAddressStock,
} from '../../../types/api'
import {
  getConnectorProfileRequirement,
  setConnectorProfileRequirement,
} from '../../../services/connectionProfileApi'
import { notify } from '../../../stores/notificationStore'

interface Loaded {
  requirement: ConnectorProfileRequirementResponse | null
  error: string | null
}

function ownerLabel(library: OwnAddressLibrary): string {
  const kind = library.ownerType === 'GROUP' ? 'Gruppe' : 'Konto'
  return library.ownerName ? `${kind} ${library.ownerName}` : kind
}

interface ProfileRequirementDialogProps {
  open: boolean
  state: ConnectorTypeStateResponse
  onClose: () => void
  onChanged: () => void
}

/**
 * Switches "Nur über Zugänge" on for a source type, or changes the choice for the libraries with
 * their own address while it holds. It names those libraries and what the requirement does not
 * cover for the type; a type that cannot be switched on shows the reason and nothing to confirm.
 */
export default function ProfileRequirementDialog({
  open,
  state,
  onClose,
  onChanged,
}: ProfileRequirementDialogProps) {
  const [loaded, setLoaded] = useState<Loaded | null>(null)
  const [stock, setStock] = useState<OwnAddressStock>(state.ownAddressStock ?? 'RUNS')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const switchingOn = !state.profileRequired

  useEffect(() => {
    if (!open) return
    let cancelled = false
    void getConnectorProfileRequirement(state.sourceType)
      .then((requirement) => {
        if (!cancelled) setLoaded({ requirement, error: null })
      })
      .catch((err) => {
        if (cancelled) return
        setLoaded({
          requirement: null,
          error: err instanceof Error ? err.message : 'Die Angaben konnten nicht geladen werden.',
        })
      })
    return () => {
      cancelled = true
    }
  }, [open, state.sourceType])

  const requirement = loaded?.requirement ?? null
  const blocked = switchingOn && requirement !== null && !requirement.switchable

  function close() {
    if (!submitting) onClose()
  }

  async function handleConfirm() {
    setSubmitting(true)
    setError(null)
    try {
      await setConnectorProfileRequirement(state.sourceType, {
        required: true,
        ownAddressStock: stock,
      })
      notify(
        switchingOn
          ? `Die Quellart „${state.displayName}“ ist jetzt nur über Zugänge nutzbar.`
          : `Die Wahl für den Bestand von „${state.displayName}“ ist gespeichert.`,
        'success',
      )
      onChanged()
      onClose()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Die Einstellung ließ sich nicht speichern.')
    } finally {
      setSubmitting(false)
    }
  }

  const libraries = requirement?.ownAddressLibraries ?? []

  return (
    <Dialog open={open} onClose={close} maxWidth="sm" fullWidth>
      <DialogTitle>
        {switchingOn
          ? `„Nur über Zugänge“ für ${state.displayName} einschalten`
          : `Bestand von ${state.displayName}: Bibliotheken mit eigener Adresse`}
      </DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {loaded === null && (
            <Typography variant="body2" sx={{ color: 'text.secondary' }}>
              Angaben werden geladen …
            </Typography>
          )}
          {loaded?.error && <Alert severity="error">{loaded.error}</Alert>}
          {error && <Alert severity="error">{error}</Alert>}
          {blocked && (
            <Alert severity="info" data-testid="profile-requirement-not-switchable">
              {requirement.notSwitchableReason ??
                'Die Pflicht lässt sich für diese Quellart derzeit nicht einschalten.'}
            </Alert>
          )}
          {requirement && !blocked && (
            <>
              {switchingOn && (
                <Typography variant="body2">
                  Danach lassen sich Bibliotheken dieser Quellart nur noch über einen Zugang
                  anlegen, testen und auflisten; eine eigene Adresse ist nicht mehr möglich, und
                  keine Bibliothek lässt sich von ihrem Zugang lösen.
                </Typography>
              )}
              {requirement.coverageNotice && (
                <Alert severity="warning" data-testid="profile-requirement-coverage">
                  {requirement.coverageNotice}
                </Alert>
              )}
              <Typography variant="subtitle2" component="h3">
                {libraries.length === 1
                  ? '1 Bibliothek mit eigener Adresse'
                  : `${libraries.length} Bibliotheken mit eigener Adresse`}
              </Typography>
              {libraries.length > 0 ? (
                <List dense disablePadding aria-label="Bibliotheken mit eigener Adresse">
                  {libraries.map((library) => (
                    <ListItem key={library.id} disableGutters>
                      <Typography variant="body2">
                        {library.name}
                        <Typography
                          component="span"
                          variant="body2"
                          sx={{ color: 'text.secondary' }}
                        >
                          {` · ${ownerLabel(library)}`}
                        </Typography>
                      </Typography>
                    </ListItem>
                  ))}
                </List>
              ) : (
                <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                  Keine Bibliothek dieser Quellart nutzt eine eigene Adresse.
                </Typography>
              )}
              <Typography variant="body2" id="profile-requirement-stock-label">
                Was geschieht mit Bibliotheken mit eigener Adresse?
              </Typography>
              <RadioGroup
                aria-labelledby="profile-requirement-stock-label"
                value={stock}
                onChange={(e) => setStock(e.target.value as OwnAddressStock)}
              >
                <FormControlLabel
                  value="RUNS"
                  control={<Radio size="small" />}
                  label="Weiterlaufen lassen – ihre Adresse ist eingefroren; eine neue erhalten sie nur über einen Zugang"
                />
                <FormControlLabel
                  value="LOCKED"
                  control={<Radio size="small" />}
                  label="Sperren – sie laufen nicht mehr, bis ihre Verwaltenden sie einem Zugang zuordnen; der Inhalt bleibt durchsuchbar"
                />
              </RadioGroup>
            </>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={close} disabled={submitting}>
          {blocked ? 'Schließen' : 'Abbrechen'}
        </Button>
        {!blocked && (
          <Button
            variant="contained"
            onClick={() => void handleConfirm()}
            disabled={submitting || requirement === null}
          >
            {switchingOn ? 'Einschalten' : 'Wahl speichern'}
          </Button>
        )}
      </DialogActions>
    </Dialog>
  )
}
