import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Typography from '@mui/material/Typography'
import type { ConnectorTypeStateResponse } from '../../../types/api'
import { notify } from '../../../stores/notificationStore'
import { listConnectorTypeStates, lockConnectorType } from '../../../services/connectionProfileApi'
import PageSection from '../../PageSection'
import { confirmLock } from './connectorLock'

/**
 * Die Sperre je Quellart (Spezifikation „Konnektor-Freigabe und Sperre“): Sie gilt für alle
 * Bibliotheken der Quellart, mit und ohne Zugang, und wird mit Rückfrage gesetzt und aufgehoben.
 */
export default function ConnectorTypeLockSection() {
  const [states, setStates] = useState<ConnectorTypeStateResponse[]>([])
  const [error, setError] = useState<string | null>(null)
  const [reloadKey, setReloadKey] = useState(0)

  useEffect(() => {
    let cancelled = false
    void listConnectorTypeStates()
      .then((loaded) => {
        if (!cancelled) {
          setStates(loaded)
          setError(null)
        }
      })
      .catch((err) => {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : 'Quellarten konnten nicht geladen werden.')
        }
      })
    return () => {
      cancelled = true
    }
  }, [reloadKey])

  async function toggle(state: ConnectorTypeStateResponse) {
    const lock = !state.locked
    if (!(await confirmLock(`die Quellart „${state.displayName}“`, lock))) return
    try {
      await lockConnectorType(state.sourceType, lock)
      notify(
        lock
          ? `Die Quellart „${state.displayName}“ ist gesperrt.`
          : `Die Sperre der Quellart „${state.displayName}“ ist aufgehoben.`,
        'success',
      )
      setReloadKey((key) => key + 1)
    } catch (err) {
      notify(err instanceof Error ? err.message : 'Die Sperre ließ sich nicht ändern.', 'error')
    }
  }

  return (
    <PageSection
      title="Quellarten"
      description="Eine gesperrte Quellart nimmt keine neuen Bibliotheken an, und ihre Bibliotheken laufen nicht mehr. Der vorhandene Inhalt bleibt durchsuchbar."
    >
      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}
      {states.length === 0 && !error ? (
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
          Quellarten werden geladen …
        </Typography>
      ) : (
        <Table size="small" aria-label="Quellarten">
          <TableHead>
            <TableRow>
              <TableCell>Quellart</TableCell>
              <TableCell>Zustand</TableCell>
              <TableCell align="right">Aktion</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {states.map((state) => (
              <TableRow key={state.sourceType}>
                <TableCell>{state.displayName}</TableCell>
                <TableCell>
                  {state.locked ? (
                    <Chip size="small" color="error" label="Gesperrt" />
                  ) : (
                    <Typography sx={{ fontSize: 13 }}>Nicht gesperrt</Typography>
                  )}
                </TableCell>
                <TableCell align="right">
                  <Button
                    size="small"
                    color={state.locked ? 'primary' : 'error'}
                    onClick={() => void toggle(state)}
                    aria-label={
                      state.locked
                        ? `Sperre der Quellart ${state.displayName} aufheben`
                        : `Quellart ${state.displayName} sperren`
                    }
                  >
                    {state.locked ? 'Entsperren' : 'Sperren'}
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </PageSection>
  )
}
