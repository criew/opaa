import Alert from '@mui/material/Alert'
import Link from '@mui/material/Link'
import { Link as RouterLink } from 'react-router'
import { CONNECTED_ACCOUNTS_ROUTE } from '@/shared/routes'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import FormControlLabel from '@mui/material/FormControlLabel'
import Radio from '@mui/material/Radio'
import RadioGroup from '@mui/material/RadioGroup'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'
import { hostOf } from './setup/connectors/common'
import type { SourceTypeDescriptor } from '@/shared/api/types'
import {
  PROFILES_FORBIDDEN_NOTICE,
  type ConnectionProfileOptionsState,
} from '@/features/connection'
import {
  OWN_ADDRESS,
  admitsLibraries,
  onOwnAccount,
  privateConnection,
  profileChoices,
} from './connectionChoice'
import { ownAddressAllowed } from './sources/sourceConnection'

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
  /**
   * The options are those of the caller's own connected accounts, for her private library: each
   * is offered once, and an empty choice leads to „Verbundene Konten“. Otherwise a profile she has
   * an account of her own on is offered a second time, as a private library, in a group of its
   * own; a profile only for persons only there.
   */
  onlyOwnAccounts?: boolean
  /** Whether the private library is a choice here at all - in the creation wizard. */
  offerPrivate?: boolean
  /** Lists a single way as well, where nothing else names it (the dialog „Zugang wechseln“). */
  showSingle?: boolean
  idPrefix: string
}

/**
 * The choice of a connection profile ("Zugang") for a library among the profiles the person may
 * use now - the server lists no other; „Eigene Adresse“ appears only where the type admits it.
 * Without any usable way the field says who sets up profiles instead.
 */
export default function ConnectionProfileSelect({
  descriptor,
  state,
  value,
  onChange,
  offerOwnAddress,
  excludeProfileId,
  onlyOwnAccounts = false,
  offerPrivate = false,
  showSingle = false,
  idPrefix,
}: ConnectionProfileSelectProps) {
  const options = state.options.filter((option) => option.id !== excludeProfileId)
  const ownOffered = offerOwnAddress && ownAddressAllowed(descriptor)
  const headingId = `${idPrefix}-connection-heading`
  const sharedOptions = onlyOwnAccounts ? options : options.filter(admitsLibraries)
  const privateOptions = offerPrivate && !onlyOwnAccounts ? options.filter(onOwnAccount) : []
  const selectable = [
    ...(ownOffered ? [OWN_ADDRESS] : []),
    ...profileChoices(sharedOptions),
    ...profileChoices(privateOptions).map(privateConnection),
  ]

  const rows: { value: string; label: string; detail: string }[] = [
    ...sharedOptions.map((option) => ({
      value: option.id,
      label: option.name,
      detail: hostOf(option.serverUrl),
    })),
    ...privateOptions.map((option) => ({
      value: privateConnection(option.id),
      label: `${option.name} · privat`,
      detail: 'nur für Sie, später nicht änderbar',
    })),
    ...(ownOffered ? [{ value: OWN_ADDRESS, label: 'Eigene Adresse', detail: '' }] : []),
  ]

  // one way needs no choice where the row „Verbindung“ names it; the private way is never chosen
  // by itself, so it is always listed
  const showList = rows.length > 1 || privateOptions.length > 0 || (showSingle && rows.length === 1)

  return (
    <Box data-testid={`${idPrefix}-connection`}>
      {state.loaded && !state.error && showList && <FieldLabel id={headingId}>Zugang</FieldLabel>}
      {!state.loaded && (
        <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mb: 1.5 }}>
          Zugänge werden geladen …
        </Typography>
      )}
      {state.error && (
        <Typography
          sx={{ fontSize: 13.5, color: state.forbidden ? 'text.secondary' : 'error.main' }}
          data-testid={`${idPrefix}-connection-error`}
        >
          {state.forbidden ? PROFILES_FORBIDDEN_NOTICE : state.error}
        </Typography>
      )}
      {showList && (
        <RadioGroup
          aria-labelledby={headingId}
          value={value ?? ''}
          onChange={(e) => onChange(e.target.value)}
        >
          {rows.map((row) => (
            <FormControlLabel
              key={row.value}
              value={row.value}
              control={<Radio size="small" />}
              sx={{ mr: 0, alignItems: 'center' }}
              label={
                <Box component="span" sx={{ display: 'flex', flexWrap: 'wrap', columnGap: 1.5 }}>
                  <Box component="span" sx={{ fontSize: 14 }}>
                    {row.label}
                  </Box>
                  {row.detail && (
                    <Box component="span" sx={{ fontSize: 13, color: 'text.secondary' }}>
                      {row.detail}
                    </Box>
                  )}
                </Box>
              }
            />
          ))}
        </RadioGroup>
      )}
      {state.error ? null : onlyOwnAccounts ? (
        state.loaded &&
        selectable.length === 0 && (
          <Alert severity="info" sx={{ mt: 1.5 }} data-testid={`${idPrefix}-connection-none`}>
            Auf keinem weiteren Zugang für „{descriptor.displayName}“ haben Sie ein verbundenes
            Konto. Konten verbinden Sie unter{' '}
            <Link component={RouterLink} to={CONNECTED_ACCOUNTS_ROUTE}>
              Verbundene Konten
            </Link>
            .
          </Alert>
        )
      ) : state.loaded && selectable.length === 0 ? (
        <Alert severity="info" sx={{ mt: 1.5 }} data-testid={`${idPrefix}-connection-none`}>
          Für die Quellart „{descriptor.displayName}“ steht Ihnen{' '}
          {excludeProfileId ? 'kein weiterer Zugang' : 'kein Zugang'} zur Verfügung; die
          Systemverwaltung richtet Zugänge ein.
        </Alert>
      ) : null}
    </Box>
  )
}
