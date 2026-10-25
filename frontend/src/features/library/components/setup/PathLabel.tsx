import { useRef, useState } from 'react'
import Box from '@mui/material/Box'
import Tooltip from '@mui/material/Tooltip'
import { PATH_SEPARATOR } from './paths'

/**
 * A name or path on one line. Too long, it is cut in the middle: the leading segments give way
 * first, the last segment keeps its full width. The full text shows as a tooltip only when something is cut.
 */
export default function PathLabel({ path }: { path: string }) {
  const ref = useRef<HTMLSpanElement>(null)
  const [cut, setCut] = useState(false)
  const at = path.lastIndexOf(PATH_SEPARATOR)
  const head = at > 0 ? path.slice(0, at) : ''
  const tail = at > 0 ? path.slice(at) : path
  const measure = () => {
    const el = ref.current
    if (!el) return
    setCut([...el.querySelectorAll('span')].some((s) => s.scrollWidth > s.clientWidth + 1))
  }
  const ellipsis = { overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'pre' } as const
  return (
    <Tooltip title={cut ? path : ''} placement="bottom-start" disableInteractive>
      <Box
        component="span"
        ref={ref}
        onMouseEnter={measure}
        onFocus={measure}
        sx={{ display: 'flex', minWidth: 0, maxWidth: '100%' }}
      >
        {head && (
          <Box component="span" sx={{ ...ellipsis, flex: '0 1 auto', minWidth: 0 }}>
            {head}
          </Box>
        )}
        <Box
          component="span"
          sx={{
            ...ellipsis,
            // the last segment never gives way to the head; only a segment wider than the line is cut
            flex: head ? '0 0 auto' : '1 1 auto',
            maxWidth: head ? 'calc(100% - 1.5em)' : '100%',
            minWidth: 0,
          }}
        >
          {tail}
        </Box>
      </Box>
    </Tooltip>
  )
}
