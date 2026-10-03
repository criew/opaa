import { useId, type ReactNode } from 'react'
import Box from '@mui/material/Box'
import IconButton from '@mui/material/IconButton'
import InputAdornment from '@mui/material/InputAdornment'
import TextField from '@mui/material/TextField'
import ToggleButton from '@mui/material/ToggleButton'
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup'
import Typography from '@mui/material/Typography'
import CloseIcon from '@mui/icons-material/Close'
import SearchIcon from '@mui/icons-material/Search'
import type { AssetType } from '../../types/api'
import type { AssetTypeDefinition } from './assetTypeRegistry'
import AssetFilterChips, { type AssetFilterKey, type AssetFilters } from './AssetFilterChips'

const ALL_TYPES = 'all'

export interface AssetFilterBarSearch {
  value: string
  onChange: (value: string) => void
  maxLength?: number
}

export interface AssetFilterBarTypes {
  offered: AssetTypeDefinition[]
  /** `undefined` is "Alle". */
  value: AssetType | undefined
  onChange: (value: AssetType | undefined) => void
}

interface AssetFilterBarProps {
  search: AssetFilterBarSearch
  /** Offered only with more than one type. */
  types?: AssetFilterBarTypes
  filters: AssetFilters
  onToggle: (key: AssetFilterKey) => void
  /** After the chips, e.g. the count of a choice. */
  trailing?: ReactNode
}

/**
 * The one filter row of catalog, asset choice and token selection (guidelines 5.11): search, then
 * the type as a titled toggle group, then the personal chips set apart from it. It wraps cleanly on
 * narrow screens. The search is named "Suchen" wherever the row stands.
 */
export default function AssetFilterBar({
  search,
  types,
  filters,
  onToggle,
  trailing,
}: AssetFilterBarProps) {
  const typeLabelId = `asset-filter-type-${useId()}`
  const showTypes = types !== undefined && types.offered.length > 1
  return (
    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, flexWrap: 'wrap' }}>
      <TextField
        size="small"
        type="search"
        value={search.value}
        onChange={(event) => search.onChange(event.target.value)}
        placeholder="Name oder Beschreibung …"
        sx={{
          flex: '1 1 240px',
          maxWidth: 420,
          '& input::-webkit-search-cancel-button': { display: 'none' },
        }}
        slotProps={{
          input: {
            startAdornment: (
              <InputAdornment position="start">
                <SearchIcon sx={{ fontSize: 16 }} />
              </InputAdornment>
            ),
            endAdornment: search.value ? (
              <InputAdornment position="end">
                <IconButton
                  size="small"
                  aria-label="Suche zurücksetzen"
                  onClick={() => search.onChange('')}
                >
                  <CloseIcon sx={{ fontSize: 16 }} />
                </IconButton>
              </InputAdornment>
            ) : undefined,
          },
          htmlInput: { 'aria-label': 'Suchen', maxLength: search.maxLength },
        }}
      />
      {showTypes && (
        <Box sx={{ display: 'inline-flex', alignItems: 'center', gap: 1 }}>
          <Typography
            id={typeLabelId}
            component="span"
            sx={{ fontSize: 12.5, color: 'text.secondary' }}
          >
            Typ
          </Typography>
          <ToggleButtonGroup
            size="small"
            exclusive
            value={types.value ?? ALL_TYPES}
            onChange={(_event, next: string | null) => {
              if (next) types.onChange(next === ALL_TYPES ? undefined : (next as AssetType))
            }}
            aria-labelledby={typeLabelId}
          >
            <ToggleButton value={ALL_TYPES} sx={{ px: 1.5 }}>
              Alle
            </ToggleButton>
            {types.offered.map((definition) => {
              const Icon = definition.Icon
              return (
                <ToggleButton
                  key={definition.type}
                  value={definition.type}
                  sx={{ px: 1.5, gap: 0.75 }}
                >
                  <Icon aria-hidden sx={{ fontSize: 16 }} />
                  {definition.label}
                </ToggleButton>
              )
            })}
          </ToggleButtonGroup>
        </Box>
      )}
      <AssetFilterChips separated={showTypes} value={filters} onToggle={onToggle} />
      {trailing}
    </Box>
  )
}
