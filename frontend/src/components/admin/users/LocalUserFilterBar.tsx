import { useEffect, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import AddIcon from '@mui/icons-material/Add'
import type { LocalAccountState, SystemRole } from '../../../types/api'
import {
  useUserAdminStore,
  type LocalUserReviewFilter,
  type LocalUserFilters,
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

const selectSx = { minWidth: 170, flex: '0 1 200px' } as const

/**
 * Suche, Filter und die eine primäre Handlung der Liste (#1541). Die drei Prüffilter der Auflage
 * aus ADR-0033 sind „Zustand: Eingeladen" (offene Einladungen), „ohne Ablaufdatum" und „länger als
 * 90 Tage nicht genutzt"; nach Aktivität wird nicht sortiert, sie ist nur eine Klasse.
 */
export default function LocalUserFilterBar({ onCreate }: { onCreate: () => void }) {
  const filters = useUserAdminStore((s) => s.filters)
  const setFilters = useUserAdminStore((s) => s.setFilters)
  /**
   * Das Suchfeld ist während des Tippens lokal und folgt dem Store, sobald dessen `query` von
   * anderer Stelle gesetzt wird (der Filter-Sprung des Auflagen-Hinweises). Abgeleitet beim
   * Rendern statt in einem Effekt synchronisiert: `basedOn` hält fest, gegen welchen Store-Wert
   * getippt wurde – ein von außen gesetzter Wert gewinnt damit sofort.
   */
  const [typed, setTyped] = useState<{ value: string; basedOn: string } | null>(null)
  const search = typed && typed.basedOn === filters.query ? typed.value : filters.query

  // Debounced: a request per keystroke would make the table flicker and would hit the rate limit
  // of the admin API for nothing. The timer is cleared on every change, so only the last one fires.
  useEffect(() => {
    if (search === filters.query) return
    const timer = setTimeout(() => void setFilters({ query: search }), SEARCH_DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [search, filters.query, setFilters])

  function update(patch: Partial<LocalUserFilters>) {
    void setFilters(patch)
  }

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
          slotProps={{ htmlInput: { 'aria-label': 'Lokale Konten suchen' } }}
        />
      </Box>
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
        sx={{ minWidth: 200, flex: '0 1 240px' }}
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
      <Button
        variant="contained"
        startIcon={<AddIcon />}
        onClick={onCreate}
        sx={{ ml: { sm: 'auto' }, flex: 'none' }}
      >
        Konto anlegen
      </Button>
    </Stack>
  )
}
