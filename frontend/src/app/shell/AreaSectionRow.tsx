import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import Box from '@mui/material/Box'
import { useTheme } from '@mui/material/styles'
import { NavLink, useLocation } from 'react-router'
import { controlHeight, darkRoles, fontSize, lightRoles, radius } from '@/shared/theme/tokens'
import { sectionState, type GlobalAreaSection } from './areaSections'

const EDGE_FADE_PX = 32

/**
 * The area navigation below 900 px (guidelines 4.7): one row of entries that scrolls sideways,
 * so the page starts right under it. The current entry is scrolled to the middle on every route
 * change; an edge fades out while there are entries beyond it. Entries stay ordinary links in a
 * list: tabbing scrolls them in. The only place that knows the narrow form of the navigation.
 */
export default function AreaSectionRow({
  title,
  sections,
}: {
  title: string
  sections: GlobalAreaSection[]
}) {
  const location = useLocation()
  const theme = useTheme()
  const roles = theme.palette.mode === 'light' ? lightRoles : darkRoles
  const rowRef = useRef<HTMLUListElement>(null)
  const [edges, setEdges] = useState({ start: false, end: false })

  const measureEdges = useCallback(() => {
    const row = rowRef.current
    if (!row) return
    setEdges({
      start: row.scrollLeft > 1,
      end: row.scrollLeft + row.clientWidth < row.scrollWidth - 1,
    })
  }, [])

  useLayoutEffect(() => {
    const row = rowRef.current
    if (!row) return
    const current = row.querySelector<HTMLElement>('[aria-current]')
    if (current) {
      row.scrollLeft = current.offsetLeft - (row.clientWidth - current.offsetWidth) / 2
    }
    measureEdges()
  }, [location.pathname, measureEdges])

  // The edges also change without a scroll: turning the phone, resizing the window, a late font.
  useEffect(() => {
    const row = rowRef.current
    if (!row || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(measureEdges)
    observer.observe(row)
    return () => observer.disconnect()
  }, [measureEdges])

  const mask = `linear-gradient(to right, ${edges.start ? 'transparent' : '#000'} 0, #000 ${EDGE_FADE_PX}px, #000 calc(100% - ${EDGE_FADE_PX}px), ${edges.end ? 'transparent' : '#000'} 100%)`

  return (
    <Box
      component="nav"
      aria-label={title}
      sx={{ bgcolor: roles.bg2, borderBottom: 1, borderColor: roles.borderStrong, py: 1 }}
    >
      <Box
        ref={rowRef}
        component="ul"
        onScroll={measureEdges}
        data-edge-start={edges.start || undefined}
        data-edge-end={edges.end || undefined}
        sx={{
          // the offset parent of the entries, so centring does not depend on the page around it
          position: 'relative',
          listStyle: 'none',
          m: 0,
          px: '12px',
          display: 'flex',
          gap: 0.5,
          overflowX: 'auto',
          // an entry reached by Tab scrolls clear of the fading edge
          scrollPaddingInline: `${EDGE_FADE_PX}px`,
          scrollbarWidth: 'none',
          '&::-webkit-scrollbar': { display: 'none' },
          // With a mouse (a desktop window at 400 % zoom) a thin scrollbar shows that the row
          // scrolls; on touch screens the swipe needs none.
          '@media (pointer: fine)': {
            scrollbarWidth: 'thin',
            '&::-webkit-scrollbar': { display: 'block', height: 6 },
          },
          maskImage: mask,
          WebkitMaskImage: mask,
        }}
      >
        {sections.map((section) => {
          const { active, current } = sectionState(section, location.pathname)
          return (
            <Box component="li" key={section.to} sx={{ flex: 'none' }}>
              <Box
                component={NavLink}
                to={section.to}
                aria-current={current}
                sx={{
                  display: 'flex',
                  alignItems: 'center',
                  minHeight: controlHeight.regular,
                  whiteSpace: 'nowrap',
                  px: '12px',
                  borderRadius: `${radius.pill}px`,
                  fontSize: fontSize.text,
                  textDecoration: 'none',
                  color: active ? 'text.primary' : 'text.secondary',
                  fontWeight: active ? 600 : 400,
                  bgcolor: active ? roles.bg1 : 'transparent',
                  border: 1,
                  borderColor: active ? roles.borderStrong : 'transparent',
                  '&:hover': { bgcolor: roles.bg1 },
                  // The row clips at its edges, so the ring stays inside the entry (4.4).
                  '&:focus-visible': { outlineOffset: '-2px' },
                }}
              >
                {section.label}
              </Box>
            </Box>
          )
        })}
      </Box>
    </Box>
  )
}
