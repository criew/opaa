import { useEffect, useState } from 'react'
import Box from '@mui/material/Box'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import type { GroupState } from '../../../types/api'
import { useOidcProviderStore } from '../../../stores/oidcProviderStore'
import { useGroupAdminListStore, type GroupFilters } from '../../../stores/groupAdminListStore'
import { SEARCH_DEBOUNCE_MS } from '../users/AccountFilterBar'
import { GROUP_STATES, GROUP_STATE_LABEL } from './groupListLabels'

const PROVIDER_OPTION_PREFIX = 'provider:'

const selectSx = { minWidth: 150, flex: '0 1 180px' } as const

/** The one select value of the origin filter, folded from the two store fields. */
function originValue(filters: GroupFilters): string {
  if (filters.providerId) return `${PROVIDER_OPTION_PREFIX}${filters.providerId}`
  return filters.origin ?? 'ALL'
}

function originPatch(value: string): Partial<GroupFilters> {
  if (value.startsWith(PROVIDER_OPTION_PREFIX)) {
    return { origin: 'PROVIDER', providerId: value.slice(PROVIDER_OPTION_PREFIX.length) }
  }
  if (value === 'INTERNAL' || value === 'PROVIDER') return { origin: value, providerId: null }
  return { origin: null, providerId: null }
}

/**
 * Suche und Filter der Gruppenliste (#1978), gebaut wie die der Kontenliste: das Suchfeld
 * entprellt, daneben Herkunft (alle, intern, alle Anbieter oder ein einzelner Anbieter) und
 * Zustand. Die primäre Handlung steht im Kopf des Bereichs darüber, damit diese Zeile nur Filter
 * trägt.
 */
export default function GroupFilterBar() {
  const filters = useGroupAdminListStore((s) => s.filters)
  const setFilters = useGroupAdminListStore((s) => s.setFilters)
  const providers = useOidcProviderStore((s) => s.providers)
  const loadProviders = useOidcProviderStore((s) => s.loadProviders)
  // Typing stays local and follows the store once its query is set elsewhere - see
  // AccountFilterBar for why this is derived while rendering rather than synced in an effect.
  const [typed, setTyped] = useState<{ value: string; basedOn: string } | null>(null)
  const search = typed && typed.basedOn === filters.query ? typed.value : filters.query

  useEffect(() => {
    void loadProviders()
  }, [loadProviders])

  useEffect(() => {
    if (search === filters.query) return
    const timer = setTimeout(() => void setFilters({ query: search }), SEARCH_DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [search, filters.query, setFilters])

  function update(patch: Partial<GroupFilters>) {
    void setFilters(patch)
  }

  // The LOCAL row is the switch of the local account management and owns no groups.
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
          placeholder="Name, Beschreibung oder Pfad suchen …"
          type="search"
          slotProps={{ htmlInput: { 'aria-label': 'Gruppen suchen' } }}
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
        <MenuItem value="ALL">Alle Gruppen</MenuItem>
        <MenuItem value="INTERNAL">Intern</MenuItem>
        <MenuItem value="PROVIDER">Alle Anbieter</MenuItem>
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
        value={filters.state ?? 'ALL'}
        onChange={(e) =>
          update({ state: e.target.value === 'ALL' ? null : (e.target.value as GroupState) })
        }
      >
        <MenuItem value="ALL">Alle Zustände</MenuItem>
        {GROUP_STATES.map((state) => (
          <MenuItem key={state} value={state}>
            {GROUP_STATE_LABEL[state]}
          </MenuItem>
        ))}
      </TextField>
    </Stack>
  )
}
