import { useEffect, useState } from 'react'
import Box from '@mui/material/Box'
import Skeleton from '@mui/material/Skeleton'
import Stack from '@mui/material/Stack'
import visuallyHidden from '@mui/utils/visuallyHidden'

/** Time before the skeleton becomes visible; a faster answer never flickers (guidelines 5.7). */
export const LOADING_DELAY_MS = 300

/**
 * The form of the content that is still loading:
 *
 * - `rows`: rows of a list or table
 * - `lines`: text lines, for value rows and settings sections
 * - `cards`: tiles of an overview grid
 * - `detail`: a title line above text lines, for a detail page or a panel
 * - `inline`: one short line inside running text, a field or a chip
 * - `block`: one area of fixed height, for a preview or a viewer
 */
export type LoadingShape = 'rows' | 'lines' | 'cards' | 'detail' | 'inline' | 'block'

const LINE_WIDTHS = ['92%', '78%', '85%', '64%', '88%', '72%']

function Shapes({ shape, count }: { shape: LoadingShape; count: number }) {
  switch (shape) {
    case 'rows':
      return (
        <Stack spacing={1}>
          {Array.from({ length: count }, (_, i) => (
            <Skeleton key={i} variant="rounded" height={48} />
          ))}
        </Stack>
      )
    case 'lines':
      return (
        <Box>
          {Array.from({ length: count }, (_, i) => (
            <Skeleton key={i} variant="text" width={LINE_WIDTHS[i % LINE_WIDTHS.length]} />
          ))}
        </Box>
      )
    case 'cards':
      return (
        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))',
            gap: 2.25,
          }}
        >
          {Array.from({ length: count }, (_, i) => (
            <Skeleton key={i} variant="rounded" height={132} />
          ))}
        </Box>
      )
    case 'detail':
      return (
        <Box>
          <Skeleton variant="text" width="40%" sx={{ mb: 1.5, transform: 'none', height: 28 }} />
          {Array.from({ length: count }, (_, i) => (
            <Skeleton key={i} variant="text" width={LINE_WIDTHS[i % LINE_WIDTHS.length]} />
          ))}
        </Box>
      )
    case 'inline':
      return <Skeleton variant="text" width="8em" sx={{ display: 'inline-block' }} />
    case 'block':
      return <Skeleton variant="rounded" height={240} />
  }
}

/**
 * The one loading state of the application (guidelines 5.7): a skeleton in the form of the
 * content, visible only after {@link LOADING_DELAY_MS}. Before that the skeleton already takes its
 * place invisibly, so neither its appearance nor the content that replaces it shifts the layout.
 * „Wird geladen" is never visible text; screen readers hear it from the status region.
 */
export default function LoadingState({
  shape = 'rows',
  count = 3,
}: {
  shape?: LoadingShape
  /** Rows, lines or tiles; ignored by `inline` and `block`. */
  count?: number
}) {
  const [visible, setVisible] = useState(false)

  useEffect(() => {
    const timer = setTimeout(() => setVisible(true), LOADING_DELAY_MS)
    return () => clearTimeout(timer)
  }, [])

  return (
    <Box
      component={shape === 'inline' ? 'span' : 'div'}
      role="status"
      aria-busy="true"
      data-loading-state={shape}
      sx={{ display: shape === 'inline' ? 'inline-block' : 'block' }}
    >
      <Box component="span" sx={visuallyHidden}>
        Wird geladen
      </Box>
      <Box
        component={shape === 'inline' ? 'span' : 'div'}
        aria-hidden="true"
        data-testid="loading-skeleton"
        sx={{
          visibility: visible ? 'visible' : 'hidden',
          display: shape === 'inline' ? 'inline-block' : 'block',
        }}
      >
        <Shapes shape={shape} count={count} />
      </Box>
    </Box>
  )
}
