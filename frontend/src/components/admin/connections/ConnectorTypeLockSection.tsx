import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import FormControlLabel from '@mui/material/FormControlLabel'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Typography from '@mui/material/Typography'
import type {
  ConnectionProfileSupport,
  ConnectorTypeStateResponse,
  OwnAddressStock,
} from '../../../types/api'
import { confirmAction } from '../../../stores/confirmStore'
import { notify } from '../../../stores/notificationStore'
import {
  lockConnectorType,
  setConnectorProfileRequirement,
} from '../../../services/connectionProfileApi'
import PageSection from '../../PageSection'
import { confirmLock } from './connectorLock'
import ProfileRequirementDialog from './ProfileRequirementDialog'

function stockLabel(stock: OwnAddressStock | null | undefined): string {
  if (stock === 'LOCKED') return 'Bibliotheken mit eigener Adresse sind gesperrt.'
  return 'Bibliotheken mit eigener Adresse laufen weiter, ihre Adresse ist eingefroren.'
}

/**
 * Die Sperre je Quellart (Spezifikation „Konnektor-Freigabe und Sperre“): Sie gilt für alle
 * Bibliotheken der Quellart, mit und ohne Zugang, und wird mit Rückfrage gesetzt und aufgehoben.
 * Daneben „Nur über Zugänge“ für eine Quellart, die Zugänge als möglich meldet: Einschalten fragt
 * nach dem Bestand mit eigener Adresse, Ausschalten nach einer Bestätigung.
 */
export default function ConnectorTypeLockSection({
  states,
  error,
  lockedProfileCount,
  onChanged,
}: {
  states: ConnectorTypeStateResponse[]
  error: string | null
  /** How many locked profiles a type has - they stay locked when the type is unlocked. */
  lockedProfileCount: (sourceType: string) => number
  onChanged: () => void
}) {
  const [requirementDialog, setRequirementDialog] = useState<ConnectorTypeStateResponse | null>(
    null,
  )

  async function toggle(state: ConnectorTypeStateResponse) {
    const lock = !state.locked
    const stillLocked = lockedProfileCount(state.sourceType)
    const remaining =
      !lock && stillLocked > 0
        ? `${stillLocked === 1 ? 'Ein Zugang' : `${stillLocked} Zugänge`} dieser Quellart ${stillLocked === 1 ? 'ist' : 'sind'} selbst gesperrt und ${stillLocked === 1 ? 'bleibt' : 'bleiben'} es; ${stillLocked === 1 ? 'seine' : 'ihre'} Bibliotheken laufen erst nach dem Entsperren ${stillLocked === 1 ? 'des Zugangs' : 'der Zugänge'} weiter. Die übrigen laufen ohne Neueinrichtung weiter.`
        : undefined
    if (!(await confirmLock(`die Quellart „${state.displayName}“`, lock, remaining))) return
    try {
      await lockConnectorType(state.sourceType, lock)
      notify(
        lock
          ? `Die Quellart „${state.displayName}“ ist gesperrt.`
          : `Die Sperre der Quellart „${state.displayName}“ ist aufgehoben.`,
        'success',
      )
      onChanged()
    } catch (err) {
      notify(err instanceof Error ? err.message : 'Die Sperre ließ sich nicht ändern.', 'error')
    }
  }

  async function switchOff(state: ConnectorTypeStateResponse) {
    const confirmed = await confirmAction({
      question: `„Nur über Zugänge“ für die Quellart „${state.displayName}“ ausschalten?`,
      consequence:
        'Neue Bibliotheken dürfen dann wieder eine eigene Adresse nutzen, und eine Bibliothek lässt sich wieder von ihrem Zugang lösen. Wegen der Pflicht gesperrte Bibliotheken laufen ohne Neueinrichtung weiter. Das Ausschalten wird als Governance-Ereignis protokolliert.',
      confirmLabel: 'Ausschalten',
      tone: 'caution',
    })
    if (!confirmed) return
    try {
      await setConnectorProfileRequirement(state.sourceType, { required: false })
      notify(
        `Für die Quellart „${state.displayName}“ ist wieder eine eigene Adresse möglich.`,
        'success',
      )
      onChanged()
    } catch (err) {
      notify(
        err instanceof Error ? err.message : 'Die Einstellung ließ sich nicht ändern.',
        'error',
      )
    }
  }

  function requirementCell(state: ConnectorTypeStateResponse) {
    const support: ConnectionProfileSupport = state.profileSupport
    switch (support) {
      case 'FORBIDDEN':
        return (
          <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
            Keine Zugänge möglich
          </Typography>
        )
      case 'REQUIRED':
        return (
          <Typography sx={{ fontSize: 13 }}>
            Immer über Zugänge (von der Quellart vorgegeben)
          </Typography>
        )
      case 'OPTIONAL':
        return (
          <Stack spacing={0.5} sx={{ alignItems: 'flex-start' }}>
            <FormControlLabel
              control={
                <Switch
                  checked={state.profileRequired}
                  onChange={() =>
                    state.profileRequired ? void switchOff(state) : setRequirementDialog(state)
                  }
                  slotProps={{
                    input: { 'aria-label': `Nur über Zugänge für ${state.displayName}` },
                  }}
                />
              }
              label="Nur über Zugänge"
            />
            {state.profileRequired && (
              <>
                <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
                  {stockLabel(state.ownAddressStock)}
                </Typography>
                <Button
                  size="small"
                  onClick={() => setRequirementDialog(state)}
                  aria-label={`Bestandswahl für ${state.displayName} ändern`}
                >
                  Bestandswahl ändern
                </Button>
              </>
            )}
          </Stack>
        )
      default: {
        const unknown: never = support
        throw new Error(`Unknown profile support ${String(unknown)}`)
      }
    }
  }

  return (
    <PageSection
      title="Quellarten"
      description="Eine gesperrte Quellart nimmt keine neuen Bibliotheken an, und ihre Bibliotheken laufen nicht mehr. Der vorhandene Inhalt bleibt durchsuchbar. „Nur über Zugänge“ lässt für eine Quellart keine eigene Adresse mehr zu."
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
              <TableCell>Zugänge</TableCell>
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
                <TableCell>{requirementCell(state)}</TableCell>
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
      {requirementDialog && (
        <ProfileRequirementDialog
          key={requirementDialog.sourceType}
          open
          state={requirementDialog}
          onClose={() => setRequirementDialog(null)}
          onChanged={onChanged}
        />
      )}
    </PageSection>
  )
}
