import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type {
  ConnectionProfileRef,
  SourceConnectionTestResponse,
  SourceTypeDescriptor,
} from '../../types/api'
import { useConnectionProfileOptions } from '../../hooks/useConnectionProfileOptions'
import { useOwnAccountProfileIds } from '../../hooks/useOwnAccountProfileIds'
import { testLibrarySource } from '../../services/libraryApi'
import { useLibraryStore } from '../../stores/libraryStore'
import { notify } from '../../stores/notificationStore'
import BusyButton from '../a11y/BusyButton'
import ConnectionProfileSelect from './ConnectionProfileSelect'
import { effectiveConnection, selectableConnections } from './connectionChoice'
import {
  addressAfterSwitch,
  addressUnder,
  framedBy,
  sourceConnectionOf,
} from './sources/sourceConnection'

/** What the dialog reads of the stored library. */
export interface ConnectableLibrary {
  sourceUrl?: string | null
  sourceSettings?: Record<string, unknown> | null
  /** Whether the library holds a secret now - a change of server discards it. */
  sourceCredentialsSet?: boolean | null
}

interface LibraryConnectionDialogProps {
  open: boolean
  onClose: () => void
  libraryId: string
  library: ConnectableLibrary
  descriptor: SourceTypeDescriptor
  /** The profile the library is connected through now; `null` for its own address. */
  current: ConnectionProfileRef | null
  /** Opens „Quelle bearbeiten“, where a discarded secret is entered anew. */
  onEditSource: () => void
  /** The library is private: only profiles with a connected account of its owner are offered. */
  privateLibrary?: boolean
}

interface Probe {
  /** The choice and address the probe ran with - a since-changed one hides it again. */
  token: string
  result?: SourceConnectionTestResponse
  error?: string
}

/** Whether a probe found the source reachable and, where it checks them, the credentials good. */
function passed(probe: Probe | null): boolean {
  return Boolean(probe?.result?.reachable && probe.result.credentialsVerified !== false)
}

/**
 * Connects a stored library through a profile ("Zugang zuordnen") or moves it to another one
 * ("Zugang wechseln"), also out of "Zugang entfernt" and out of the lock of its own address. The
 * new profile is tested with the library's address before anything is saved; after a failed test
 * only an explicit „Trotzdem zuordnen“ saves. An address under neither profile is entered here.
 */
export default function LibraryConnectionDialog({
  open,
  onClose,
  libraryId,
  library,
  descriptor,
  current,
  onEditSource,
  privateLibrary = false,
}: LibraryConnectionDialogProps) {
  const connectLibraryToProfile = useLibraryStore((s) => s.connectLibraryToProfile)
  const listed = useConnectionProfileOptions(open ? descriptor.type : null, libraryId)
  // A private library moves only to a profile its owner has a connected account on.
  const ownAccountProfileIds = useOwnAccountProfileIds(open && privateLibrary)
  const state = privateLibrary
    ? {
        ...listed,
        loaded: listed.loaded && ownAccountProfileIds !== null,
        options: listed.options.filter((option) => ownAccountProfileIds?.includes(option.id)),
      }
    : listed
  const [chosen, setChosen] = useState<string | null>(null)
  const [enteredAddress, setEnteredAddress] = useState<Record<string, string>>({})
  const [probe, setProbe] = useState<Probe | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [testing, setTesting] = useState(false)
  const [submitting, setSubmitting] = useState(false)

  const others = state.options.filter((option) => option.id !== current?.id)
  const choice = effectiveConnection(chosen, selectableConnections(descriptor, others, false))
  const chosenOption = others.find((option) => option.id === choice)
  const next = chosenOption ? sourceConnectionOf(chosenOption) : null
  const proposed = next ? addressAfterSwitch(library.sourceUrl, current?.serverUrl, next) : null
  const repair = next !== null && proposed === null
  const address = next ? (proposed ?? enteredAddress[next.profileId] ?? next.serverUrl) : ''
  const addressFits = next !== null && addressUnder(address, next.serverUrl)
  const token = JSON.stringify({ choice, address })
  const visible = probe?.token === token ? probe : null
  const busy = testing || submitting

  function close() {
    if (!busy) onClose()
  }

  async function test(): Promise<Probe | null> {
    if (!next || !addressFits) return null
    setTesting(true)
    setError(null)
    let outcome: Probe
    try {
      const request = framedBy(
        {
          sourceType: descriptor.type,
          sourceUrl: address.trim(),
          sourceSettings: library.sourceSettings ?? undefined,
          sourceInsecureSsl: false,
        },
        next,
        true,
      )
      const result = await testLibrarySource({
        ...request,
        libraryId,
        connectionProfileId: next.profileId,
      })
      outcome = { token, result }
    } catch (err) {
      outcome = {
        token,
        error: err instanceof Error ? err.message : 'Die Verbindung ließ sich nicht prüfen.',
      }
    } finally {
      setTesting(false)
    }
    setProbe(outcome)
    return outcome
  }

  async function connect(checked: Probe | null) {
    if (!next) return
    const name = next.name
    setSubmitting(true)
    setError(null)
    try {
      const connected = await connectLibraryToProfile(libraryId, next.profileId, address.trim())
      const tested = passed(checked) && checked?.result ? ` ${checked.result.message}` : ''
      if (library.sourceCredentialsSet && connected && !connected.sourceCredentialsSet) {
        notify(
          `Die Bibliothek ist jetzt über den Zugang „${name}“ verbunden. Weil sich dabei der Server geändert hat, wurden die hinterlegten Zugangsdaten verworfen; bis sie neu eingetragen sind, läuft die Bibliothek nicht.`,
          'warning',
          { label: 'Quelle bearbeiten', onClick: onEditSource },
        )
      } else {
        notify(`Die Bibliothek ist jetzt über den Zugang „${name}“ verbunden.${tested}`, 'success')
      }
      onClose()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Der Zugang ließ sich nicht zuordnen.')
    } finally {
      setSubmitting(false)
    }
  }

  async function handleConnect() {
    const checked = visible ?? (await test())
    if (passed(checked)) await connect(checked)
  }

  const failed = visible !== null && !passed(visible)

  return (
    <Dialog open={open} onClose={close} maxWidth="sm" fullWidth>
      <DialogTitle>{current ? 'Zugang wechseln' : 'Zugang zuordnen'}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <Typography variant="body2">
            Die Adresse der Bibliothek muss unter der Server-Adresse des gewählten Zugangs liegen;
            eine Adresse unter dem bisherigen Zugang wandert mit. Wechselt dabei der Server, werden
            die hinterlegten Zugangsdaten verworfen und müssen neu eingetragen werden. Vor dem
            Speichern prüft OPAA die Verbindung über den gewählten Zugang.
          </Typography>
          {error && <Alert severity="error">{error}</Alert>}
          <ConnectionProfileSelect
            descriptor={descriptor}
            state={state}
            value={choice}
            onChange={(value) => {
              setChosen(value)
              setError(null)
            }}
            offerOwnAddress={false}
            excludeProfileId={current?.id}
            idPrefix="library-connection"
          />
          {next && !repair && (
            <Typography variant="body2" data-testid="library-connection-address">
              Adresse nach dem Zuordnen: <code>{address}</code>
            </Typography>
          )}
          {next && repair && (
            <>
              <Alert severity="info">
                Die bisherige Adresse
                {library.sourceUrl ? (
                  <>
                    {' '}
                    <code>{library.sourceUrl}</code>
                  </>
                ) : null}{' '}
                liegt nicht unter dem Zugang „{next.name}“. Tragen Sie die Adresse ein, unter der
                die Bibliothek künftig liest.
              </Alert>
              <TextField
                label="Neue Adresse"
                size="small"
                fullWidth
                value={address}
                onChange={(e) =>
                  setEnteredAddress((prev) => ({ ...prev, [next.profileId]: e.target.value }))
                }
                error={!addressFits}
                helperText={
                  addressFits
                    ? `Liegt unter der Server-Adresse des Zugangs: ${next.serverUrl}`
                    : `Muss unter der Server-Adresse des Zugangs liegen: ${next.serverUrl}`
                }
                slotProps={{ htmlInput: { maxLength: 2000, sx: { fontFamily: 'monospace' } } }}
              />
            </>
          )}
          {next && (
            <BusyButton
              variant="outlined"
              onClick={() => void test()}
              disabled={!addressFits || submitting}
              busy={testing}
              busyAnnouncement="Verbindung wird geprüft"
              sx={{ alignSelf: 'flex-start' }}
            >
              {testing ? 'Verbindung wird geprüft …' : 'Verbindung prüfen'}
            </BusyButton>
          )}
          {visible?.result && (
            <Alert
              severity={passed(visible) ? 'success' : 'warning'}
              data-testid="library-connection-test"
            >
              {visible.result.message}
            </Alert>
          )}
          {visible?.error && (
            <Alert severity="error" data-testid="library-connection-test">
              {visible.error}
            </Alert>
          )}
          {failed && (
            <Typography variant="body2" sx={{ color: 'text.secondary' }}>
              Die Prüfung ist nicht gelungen. Zuordnen lässt sich die Bibliothek trotzdem; sie läuft
              dann erst, wenn die Verbindung steht.
            </Typography>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={close} disabled={busy}>
          Abbrechen
        </Button>
        {failed ? (
          <BusyButton
            variant="contained"
            color="warning"
            onClick={() => void connect(visible)}
            busy={submitting}
            busyAnnouncement="Zugang wird zugeordnet"
          >
            {submitting ? 'Wird zugeordnet …' : 'Trotzdem zuordnen'}
          </BusyButton>
        ) : (
          <BusyButton
            variant="contained"
            onClick={() => void handleConnect()}
            disabled={choice === null || !addressFits}
            busy={busy}
            busyAnnouncement={testing ? 'Verbindung wird geprüft' : 'Zugang wird zugeordnet'}
          >
            {submitting ? 'Wird zugeordnet …' : 'Prüfen und zuordnen'}
          </BusyButton>
        )}
      </DialogActions>
    </Dialog>
  )
}
