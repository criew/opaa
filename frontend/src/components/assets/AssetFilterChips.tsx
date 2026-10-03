import Chip from '@mui/material/Chip'
import Divider from '@mui/material/Divider'
import Stack from '@mui/material/Stack'
import StarIcon from '@mui/icons-material/Star'
import StarBorderIcon from '@mui/icons-material/StarBorder'

/** The personal asset filters. Each only narrows; switched on together they combine with AND. */
export interface AssetFilters {
  favorites: boolean
  /** Only where something is being chosen; left undefined, its chip is not offered. */
  selectedOnly?: boolean
}

export type AssetFilterKey = keyof AssetFilters

interface AssetFilterChipsProps {
  value: AssetFilters
  onToggle: (key: AssetFilterKey) => void
  /** Sets the chips apart from a type group before them with a vertical rule. */
  separated?: boolean
}

/**
 * The personal end of the one filter row of catalog, asset choice and token selection (guidelines
 * 5.11): independent toggle chips whose state `aria-pressed` carries, after search and type. All
 * off is "alle".
 */
export default function AssetFilterChips({
  value,
  onToggle,
  separated = false,
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
      {separated && <Divider orientation="vertical" flexItem sx={{ mr: 0.5 }} />}
      <Chip
        label="Favoriten"
        icon={value.favorites ? <StarIcon /> : <StarBorderIcon />}
        color={value.favorites ? 'primary' : 'default'}
        variant={value.favorites ? 'filled' : 'outlined'}
        aria-pressed={value.favorites}
        onClick={() => onToggle('favorites')}
      />
      {value.selectedOnly !== undefined && (
        <Chip
          label="Nur ausgewählte"
          variant={value.selectedOnly ? 'filled' : 'outlined'}
          aria-pressed={value.selectedOnly}
          onClick={() => onToggle('selectedOnly')}
        />
      )}
    </Stack>
  )
}
