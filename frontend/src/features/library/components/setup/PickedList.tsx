import Box from '@mui/material/Box'
import IconButton from '@mui/material/IconButton'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import CloseIcon from '@mui/icons-material/Close'
import ErrorOutlineIcon from '@mui/icons-material/ErrorOutlineOutlined'
import { pathOf, type PickedItem } from './pickerTypes'

interface PickedListProps {
  items: PickedItem[]
  rootLabel: string
  /** Absent for a read-only list (Prüfen, Reiter „Quelle"). */
  onRemove?: (item: PickedItem) => void
  /** Why a chosen object could not be read at the last test, by key. */
  problems?: Record<string, string>
  /** Shows at most this many and „und n weitere" below; all by default. */
  limit?: number
}

/**
 * The chosen objects of a source, each with its full path: the right column of the selection, the
 * scope on the review step and on the Reiter „Quelle". Names that do not fit are cut with an
 * ellipsis; the full path stays in the tooltip and the accessible name.
 */
export default function PickedList({
  items,
  rootLabel,
  onRemove,
  problems,
  limit,
}: PickedListProps) {
  const shown = limit ? items.slice(0, limit) : items
  return (
    <Box>
      <Box component="ul" sx={{ listStyle: 'none', m: 0, p: 0 }}>
        {shown.map((item) => {
          const path = pathOf(item, rootLabel)
          const problem = problems?.[item.key]
          return (
            <Box
              component="li"
              key={item.key}
              sx={{
                display: 'flex',
                alignItems: 'flex-start',
                gap: 1,
                py: 1,
                borderBottom: 1,
                borderColor: 'divider',
                '&:last-of-type': { borderBottom: 0 },
              }}
            >
              <Box sx={{ minWidth: 0, flex: 1 }}>
                <Tooltip describeChild title={path} placement="top-start" enterDelay={500}>
                  <Typography
                    sx={{
                      fontSize: 13.5,
                      fontWeight: 500,
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                      whiteSpace: 'nowrap',
                    }}
                  >
                    {item.name}
                  </Typography>
                </Tooltip>
                {item.trail.length > 0 && (
                  <Typography
                    sx={{
                      fontSize: 12,
                      color: 'text.secondary',
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                      whiteSpace: 'nowrap',
                    }}
                  >
                    {[rootLabel, ...item.trail.map((crumb) => crumb.name)].join(' › ')}
                  </Typography>
                )}
                {problem && (
                  <Box sx={{ display: 'flex', gap: 0.5, alignItems: 'flex-start', mt: 0.5 }}>
                    <ErrorOutlineIcon aria-hidden sx={{ fontSize: 16, color: 'error.main' }} />
                    <Typography sx={{ fontSize: 12 }}>{problem}</Typography>
                  </Box>
                )}
              </Box>
              {onRemove && (
                <Tooltip describeChild title="Entfernen">
                  <IconButton
                    size="small"
                    aria-label={`„${item.name}“ entfernen`}
                    onClick={() => onRemove(item)}
                  >
                    <CloseIcon fontSize="small" />
                  </IconButton>
                </Tooltip>
              )}
            </Box>
          )
        })}
      </Box>
      {limit && items.length > limit && (
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 0.5 }}>
          und {(items.length - limit).toLocaleString('de-DE')} weitere
        </Typography>
      )}
    </Box>
  )
}
