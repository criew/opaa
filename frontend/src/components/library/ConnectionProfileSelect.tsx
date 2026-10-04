import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import EditLocationAltOutlinedIcon from '@mui/icons-material/EditLocationAltOutlined'
import VpnKeyOutlinedIcon from '@mui/icons-material/VpnKeyOutlined'
import type {
  ConnectionProfileOption,
  ProfileDefaultKey,
  SourceTypeDescriptor,
} from '../../types/api'
import type { ConnectionProfileOptionsState } from '../../hooks/useConnectionProfileOptions'
import ChoiceTileGroup, { type ChoiceTile } from '../choice/ChoiceTileGroup'
import { AUTH_METHOD_LABELS } from '../admin/connections/connectionProfileLabels'
import { OWN_ADDRESS, selectableConnections } from './connectionChoice'
import { ownAddressAllowed } from './sources/sourceConnection'

const NOT_RELEASED =
  'Dieser Zugang ist für Sie nicht freigegeben. Freigaben erteilt die Systemverwaltung.'

function defaultValueLabel(key: ProfileDefaultKey | undefined, value: unknown): string {
  if (key === undefined) return String(value)
  const kind = key.kind
  switch (kind) {
    case 'BOOLEAN':
      return value === true ? 'Ja' : 'Nein'
    case 'TEXT':
    case 'CHOICE':
      return String(value)
    default: {
      const unknown: never = kind
      throw new Error(`Unknown profile default kind ${String(unknown)}`)
    }
  }
}

function profileDescription(option: ConnectionProfileOption, descriptor: SourceTypeDescriptor) {
  const defaults = Object.entries(option.connectorDefaults ?? {}).map(([key, value]) => {
    const declared = descriptor.profileDefaults.find((d) => d.key === key)
    return `${declared?.label ?? key}: ${defaultValueLabel(declared, value)}`
  })
  return (
    <>
      <Box component="span" sx={{ display: 'block', wordBreak: 'break-all' }}>
        {option.serverUrl}
      </Box>
      <Box component="span" sx={{ display: 'block' }}>
        Anmeldung: {AUTH_METHOD_LABELS[option.authMethod]}
        {defaults.length > 0 && ` · Vorgaben: ${defaults.join(', ')}`}
      </Box>
    </>
  )
}

interface ConnectionProfileSelectProps {
  descriptor: SourceTypeDescriptor
  state: ConnectionProfileOptionsState
  /** The choice in effect ({@link effectiveConnection}). */
  value: string | null
  onChange: (value: string) => void
  /** Whether „Eigene Adresse“ belongs to the choice at all; it is offered only where admitted. */
  offerOwnAddress: boolean
  /** A profile not to offer, such as the one a library is already connected through. */
  excludeProfileId?: string
  idPrefix: string
}

/**
 * The choice of a connection profile ("Zugang") for a library. A profile the person may not use
 * stays visible with the notice naming who releases it; „Eigene Adresse“ appears only where the
 * type admits it. Without any usable way the field says who sets up profiles instead.
 */
export default function ConnectionProfileSelect({
  descriptor,
  state,
  value,
  onChange,
  offerOwnAddress,
  excludeProfileId,
  idPrefix,
}: ConnectionProfileSelectProps) {
  const options = state.options.filter((option) => option.id !== excludeProfileId)
  const ownOffered = offerOwnAddress && ownAddressAllowed(descriptor)
  const selectable = selectableConnections(descriptor, options, offerOwnAddress)
  const headingId = `${idPrefix}-connection-heading`

  const tiles: ChoiceTile<string>[] = [
    ...(ownOffered
      ? [
          {
            value: OWN_ADDRESS,
            label: 'Eigene Adresse',
            description: 'Adresse und Zugangsdaten tragen Sie selbst ein.',
            icon: <EditLocationAltOutlinedIcon sx={{ fontSize: 22 }} />,
          },
        ]
      : []),
    ...options.map((option) => ({
      value: option.id,
      label: option.name,
      description: profileDescription(option, descriptor),
      icon: <VpnKeyOutlinedIcon sx={{ fontSize: 22 }} />,
      disabledReason: option.creatable ? null : (option.creationNotice ?? NOT_RELEASED),
    })),
  ]

  return (
    <Box data-testid={`${idPrefix}-connection`}>
      <Typography id={headingId} component="h3" sx={{ fontSize: 16, fontWeight: 600, mb: 1 }}>
        Zugang
      </Typography>
      {offerOwnAddress && !ownOffered && (
        <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mb: 1.5 }}>
          {descriptor.profileRequired
            ? `Die Quellart „${descriptor.displayName}“ ist nur über einen Zugang nutzbar; eine eigene Adresse ist nicht möglich.`
            : `Mit eigener Adresse dürfen Sie die Quellart „${descriptor.displayName}“ nicht anlegen, nur über einen für Sie freigegebenen Zugang.`}
        </Typography>
      )}
      {!state.loaded && (
        <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mb: 1.5 }}>
          Zugänge werden geladen …
        </Typography>
      )}
      {state.error && (
        <Alert severity="error" sx={{ mb: 1.5 }}>
          {state.error}
        </Alert>
      )}
      {tiles.length > 0 && (
        <ChoiceTileGroup<string>
          aria-labelledby={headingId}
          value={value}
          onChange={onChange}
          tiles={tiles}
        />
      )}
      {state.loaded && selectable.length === 0 ? (
        <Alert severity="info" sx={{ mt: 1.5 }} data-testid={`${idPrefix}-connection-none`}>
          Für die Quellart „{descriptor.displayName}“ steht Ihnen kein Zugang zur Verfügung. Zugänge
          legt die Systemverwaltung an und gibt sie frei; bitte wenden Sie sich an sie.
        </Alert>
      ) : (
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 1 }}>
          Zugänge legt die Systemverwaltung an und gibt sie frei.
        </Typography>
      )}
    </Box>
  )
}
