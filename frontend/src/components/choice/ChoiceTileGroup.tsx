import type { KeyboardEvent as ReactKeyboardEvent, ReactNode } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { alpha } from '@mui/material/styles'
import CheckCircleIcon from '@mui/icons-material/CheckCircle'
import { blue } from '../../theme/tokens'

export interface ChoiceTile<K extends string> {
  value: K
  /** The tile's name: one word or a short phrase. */
  label: string
  /** The short sentence under the name. */
  description?: ReactNode
  /** Decorative; the label carries the meaning. */
  icon: ReactNode
  /** Why the tile cannot be chosen. The tile stays visible, greyed out, with this reason. */
  disabledReason?: string | null
}

interface CommonProps<K extends string> {
  tiles: ChoiceTile<K>[]
  /** The group's accessible name, unless `aria-labelledby` points at a visible title. */
  'aria-label'?: string
  'aria-labelledby'?: string
  /** Columns from the `sm` breakpoint on; a narrow viewport always stacks the tiles. */
  columns?: number
}

interface SingleProps<K extends string> extends CommonProps<K> {
  multiple?: false
  value: K | null
  onChange: (value: K) => void
}

interface MultipleProps<K extends string> extends CommonProps<K> {
  multiple: true
  value: K[]
  onChange: (value: K[]) => void
}

export type ChoiceTileGroupProps<K extends string> = SingleProps<K> | MultipleProps<K>

const TILE_ATTRIBUTE = 'data-choice-tile'

/**
 * The tile choice with icon (guidelines 5.11). A single choice is a `radiogroup` with one tab stop:
 * the arrow keys, Home and End move the choice and the focus, skipping locked tiles. A multiple
 * choice is a group of `checkbox` tiles, each its own tab stop. Space and Enter choose in both.
 */
export default function ChoiceTileGroup<K extends string>(props: ChoiceTileGroupProps<K>) {
  const { tiles, columns = 2 } = props
  const selectable = tiles.filter((tile) => !tile.disabledReason).map((tile) => tile.value)

  const isSelected = (value: K) =>
    props.multiple ? props.value.includes(value) : props.value === value

  // Without a chosen, selectable tile the first selectable one holds the group's tab stop -
  // otherwise the group could not be reached by keyboard at all.
  const tabStop =
    !props.multiple && props.value !== null && selectable.includes(props.value)
      ? props.value
      : selectable[0]

  function choose(value: K) {
    if (props.multiple) {
      props.onChange(
        props.value.includes(value)
          ? props.value.filter((v) => v !== value)
          : [...props.value, value],
      )
    } else {
      props.onChange(value)
    }
  }

  function handleKeyDown(event: ReactKeyboardEvent<HTMLDivElement>) {
    if (props.multiple || selectable.length === 0) return
    const forward = event.key === 'ArrowRight' || event.key === 'ArrowDown'
    const backward = event.key === 'ArrowLeft' || event.key === 'ArrowUp'
    const home = event.key === 'Home'
    const end = event.key === 'End'
    if (!forward && !backward && !home && !end) return
    event.preventDefault()
    const current = props.value === null ? -1 : selectable.indexOf(props.value)
    const count = selectable.length
    const next = home
      ? selectable[0]
      : end
        ? selectable[count - 1]
        : current === -1
          ? selectable[forward ? 0 : count - 1]
          : selectable[(current + (forward ? 1 : count - 1)) % count]
    props.onChange(next)
    Array.from(event.currentTarget.querySelectorAll<HTMLButtonElement>(`[${TILE_ATTRIBUTE}]`))
      .find((element) => element.getAttribute(TILE_ATTRIBUTE) === next)
      ?.focus()
  }

  return (
    <Box
      role={props.multiple ? 'group' : 'radiogroup'}
      aria-label={props['aria-label']}
      aria-labelledby={props['aria-labelledby']}
      onKeyDown={handleKeyDown}
      sx={{
        display: 'grid',
        gridTemplateColumns: { xs: '1fr', sm: `repeat(${columns}, 1fr)` },
        gap: '14px',
      }}
    >
      {tiles.map((tile) => {
        const selected = isSelected(tile.value)
        const locked = Boolean(tile.disabledReason)
        return (
          <Box
            key={tile.value}
            component="button"
            type="button"
            role={props.multiple ? 'checkbox' : 'radio'}
            {...{ [TILE_ATTRIBUTE]: tile.value }}
            aria-checked={selected}
            aria-disabled={locked}
            disabled={locked}
            // A radio group has exactly one tab stop (roving tabindex); a checkbox group one per tile.
            tabIndex={props.multiple ? 0 : tile.value === tabStop ? 0 : -1}
            onClick={() => {
              if (!locked) choose(tile.value)
            }}
            sx={{
              position: 'relative',
              display: 'flex',
              alignItems: 'flex-start',
              gap: '12px',
              textAlign: 'left',
              font: 'inherit',
              cursor: locked ? 'not-allowed' : 'pointer',
              opacity: locked ? 0.6 : 1,
              p: 2,
              pr: 4.5,
              border: selected ? 2 : 1,
              borderColor: selected ? 'primary.main' : 'divider',
              borderRadius: '10px',
              color: 'text.primary',
              bgcolor: selected
                ? (theme) =>
                    theme.palette.mode === 'dark'
                      ? alpha(theme.palette.primary.main, 0.16)
                      : blue[50]
                : 'transparent',
              '&:hover': { borderColor: selected ? 'primary.main' : 'text.disabled' },
            }}
          >
            <Box aria-hidden sx={{ display: 'flex', color: 'text.secondary', mt: '2px' }}>
              {tile.icon}
            </Box>
            <Box>
              <Typography sx={{ fontSize: 14.5, fontWeight: 600 }}>{tile.label}</Typography>
              {tile.description && (
                <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 0.25 }}>
                  {tile.description}
                </Typography>
              )}
              {tile.disabledReason && (
                <Typography sx={{ fontSize: 12.5, color: 'warning.main', mt: 0.5 }}>
                  {tile.disabledReason}
                </Typography>
              )}
            </Box>
            {/* Chosen shows as border and check mark, never by colour alone (guidelines 5.11). */}
            {selected && (
              <CheckCircleIcon
                aria-hidden
                sx={{
                  position: 'absolute',
                  top: 10,
                  right: 10,
                  fontSize: 18,
                  color: 'primary.main',
                }}
              />
            )}
          </Box>
        )
      })}
    </Box>
  )
}
