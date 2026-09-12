import { useEffect, useState } from 'react'
import Box from '@mui/material/Box'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import type { LocalAccountState, SystemRole } from '../../../types/api'
import { useOidcProviderStore } from '../../../stores/oidcProviderStore'
import {
  useUserAdminStore,
  type AccountFilters,
  type LocalUserReviewFilter,
} from '../../../stores/userAdminStore'
import {
  LOCAL_ACCOUNT_STATE_LABEL,
  LOCAL_ACCOUNT_STATES,
  SYSTEM_ROLES,
  SYSTEM_ROLE_LABEL,
} from './localUserLabels'

/** How long a keystroke waits before it becomes a request (#1541: 300 ms entprellt). */
export const SEARCH_DEBOUNCE_MS = 300

const REVIEW_OPTIONS: Array<{ value: LocalUserReviewFilter; label: string }> = [
  { value: 'ALL', label: 'Alle Konten' },
  { value: 'WITHOUT_EXPIRY', label: 'Ohne Ablaufdatum' },
  { value: 'INACTIVE', label: 'Länger als 90 Tage nicht genutzt' },
]

const PROVIDER_OPTION_PREFIX = 'provider:'

const selectSx = { minWidth: 150, flex: '0 1 180px' } as const

/** The one select value of the origin filter, folded from the two store fields. */
function originValue(filters: AccountFilters): string {
  if (filters.providerId) return `${PROVIDER_OPTION_PREFIX}${filters.providerId}`
  return filters.providerType ?? 'ALL'
}

function originPatch(value: string): Partial<AccountFilters> {
  if (value.startsWith(PROVIDER_OPTION_PREFIX)) {
    return { providerType: 'OIDC', providerId: value.slice(PROVIDER_OPTION_PREFIX.length) }
  }
  if (value === 'LOCAL' || value === 'OIDC') return { providerType: value, providerId: null }
  return { providerType: null, providerId: null }
}

/**
 * Suche und Filter der Kontenliste (#1541, #1601); die primäre Handlung steht im Kopf des
 * Bereichs darüber, damit diese Zeile nur Filter trägt. Die Herkunft wählt
 * zwischen allen, lokalen und Anbieterkonten oder einem einzelnen Anbieter; die drei Prüffilter
 * der Auflage aus ADR-0033 - „Zustand: Eingeladen", „ohne Ablaufdatum", „länger als 90 Tage nicht
 * genutzt" - beschreiben lokale Konten und grenzen die Liste auf sie ein. Nach Aktivität wird
 * nicht sortiert, sie ist nur eine Klasse.
 */
export default function AccountFilterBar() {
  const filters = useUserAdminStore((s) => s.filters)
  const setFilters = useUserAdminStore((s) => s.setFilters)
  const providers = useOidcProviderStore((s) => s.providers)
  const loadProviders = useOidcProviderStore((s) => s.loadProviders)
  /**
   * Das Suchfeld ist während des Tippens lokal und folgt dem Store, sobald dessen `query` von
   * anderer Stelle gesetzt wird (der Filter-Sprung des Auflagen-Hinweises). Abgeleitet beim
   * Rendern statt in einem Effekt synchronisiert: `basedOn` hält fest, gegen welchen Store-Wert
   * getippt wurde – ein von außen gesetzter Wert gewinnt damit sofort.
   */
  const [typed, setTyped] = useState<{ value: string; basedOn: string } | null>(null)
  const search = typed && typed.basedOn === filters.query ? typed.value : filters.query

  useEffect(() => {
    void loadProviders()
  }, [loadProviders])

  // Debounced: a request per keystroke would make the table flicker and would hit the rate limit
  // of the admin API for nothing. The timer is cleared on every change, so only the last one fires.
  useEffect(() => {
    if (search === filters.query) return
    const timer = setTimeout(() => void setFilters({ query: search }), SEARCH_DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [search, filters.query, setFilters])

  function update(patch: Partial<AccountFilters>) {
    void setFilters(patch)
  }

  // The LOCAL row is the switch of the local account management, not a provider to pick here.
  const providerOptions = providers.filter((provider) => provider.providerType !== 'LOCAL')

  return (
    <Stack
      direction="row"
      spacing={1.5}
      sx={{ flexWrap: 'wrap', rowGap: 1.5, alignItems: 'flex-end', mb: 2 }}
    >
      <Box sx={{ flex: '1 1 220px', minWidth: 180 }}>
        <TextField
          size="small"
          fullWidth
          value={search}
          onChange={(e) => setTyped({ value: e.target.value, basedOn: filters.query })}
          placeholder="Name oder E-Mail-Adresse suchen …"
          type="search"
          slotProps={{ htmlInput: { 'aria-label': 'Konten suchen' } }}
        />
      </Box>
      <TextField
        size="small"
        select
        sx={selectSx}
        label="Herkunft"
        value={originValue(filters)}
        onChange={(e) => update(originPatch(e.target.value))}
      >
        <MenuItem value="ALL">Alle Konten</MenuItem>
        <MenuItem value="LOCAL">Lokal</MenuItem>
        <MenuItem value="OIDC">Alle Anbieter</MenuItem>
        {providerOptions.map((provider) => (
          <MenuItem key={provider.id} value={`${PROVIDER_OPTION_PREFIX}${provider.id}`}>
            {provider.displayName}
            {provider.enabled ? '' : ' (deaktiviert)'}
          </MenuItem>
        ))}
      </TextField>
      <TextField
        size="small"
        select
        sx={selectSx}
        label="Zustand"
        value={filters.status ?? 'ALL'}
        onChange={(e) =>
          update({
            status: e.target.value === 'ALL' ? null : (e.target.value as LocalAccountState),
          })
        }
      >
        <MenuItem value="ALL">Alle Zustände</MenuItem>
        {LOCAL_ACCOUNT_STATES.map((state) => (
          <MenuItem key={state} value={state}>
            {LOCAL_ACCOUNT_STATE_LABEL[state]}
          </MenuItem>
        ))}
      </TextField>
      <TextField
        size="small"
        select
        sx={selectSx}
        label="Rolle"
        value={filters.role ?? 'ALL'}
        onChange={(e) =>
          update({ role: e.target.value === 'ALL' ? null : (e.target.value as SystemRole) })
        }
      >
        <MenuItem value="ALL">Alle Rollen</MenuItem>
        {SYSTEM_ROLES.map((role) => (
          <MenuItem key={role} value={role}>
            {SYSTEM_ROLE_LABEL[role]}
          </MenuItem>
        ))}
      </TextField>
      <TextField
        size="small"
        select
        sx={{ minWidth: 180, flex: '0 1 220px' }}
        label="Auflage"
        value={filters.review}
        onChange={(e) => update({ review: e.target.value as LocalUserReviewFilter })}
      >
        {REVIEW_OPTIONS.map((option) => (
          <MenuItem key={option.value} value={option.value}>
            {option.label}
          </MenuItem>
        ))}
      </TextField>
    </Stack>
  )
}
