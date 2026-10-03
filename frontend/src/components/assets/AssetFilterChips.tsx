import Chip from '@mui/material/Chip'
import Stack from '@mui/material/Stack'
import StarIcon from '@mui/icons-material/Star'
import StarBorderIcon from '@mui/icons-material/StarBorder'

/** The personal asset filters. Each only narrows; switched on together they combine with AND. */
export interface AssetFilters {
  favorites: boolean
  fromMyGroups: boolean
  /** Only where something is being chosen; left undefined, its chip is not offered. */
  selectedOnly?: boolean
}

export type AssetFilterKey = keyof AssetFilters

interface AssetFilterChipsProps {
  value: AssetFilters
  onToggle: (key: AssetFilterKey) => void
  /** The name of the `selectedOnly` chip, after what choosing means in its place. */
  selectedOnlyLabel?: string
}

/**
 * The one filter bar of catalog, asset choice and token selection (guidelines 5.11): independent
 * toggle chips whose state `aria-pressed` carries. All off is "alle".
 */
export default function AssetFilterChips({
  value,
  onToggle,
  selectedOnlyLabel = 'Nur ausgewählte',
}: AssetFilterChipsProps) {
  return (
    <Stack
      direction="row"
      spacing={1}
      useFlexGap
      role="group"
      aria-label="Filter"
      sx={{ flexWrap: 'wrap', alignItems: 'center' }}
    >
      <Chip
        label="Favoriten"
        icon={value.favorites ? <StarIcon /> : <StarBorderIcon />}
        variant={value.favorites ? 'filled' : 'outlined'}
        aria-pressed={value.favorites}
        onClick={() => onToggle('favorites')}
      />
      <Chip
        label="Aus meinen Gruppen"
        variant={value.fromMyGroups ? 'filled' : 'outlined'}
        aria-pressed={value.fromMyGroups}
        onClick={() => onToggle('fromMyGroups')}
      />
      {value.selectedOnly !== undefined && (
        <Chip
          label={selectedOnlyLabel}
          variant={value.selectedOnly ? 'filled' : 'outlined'}
          aria-pressed={value.selectedOnly}
          onClick={() => onToggle('selectedOnly')}
        />
      )}
    </Stack>
  )
}
