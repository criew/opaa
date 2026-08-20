import { useEffect, useRef } from 'react'
import Typography from '@mui/material/Typography'
import type { TypographyProps } from '@mui/material/Typography'
import visuallyHidden from '@mui/utils/visuallyHidden'
import { usePageTitle } from '../../hooks/usePageTitle'

export const MAIN_CONTENT_ID = 'main-content'

interface PageHeadingProps {
  /** Visible heading text; also becomes the document title unless `documentTitle` is given. */
  title: string
  /** Overrides the document title when it should be more specific than the heading. */
  documentTitle?: string
  /** Visual size only - the element is always the page's single <h1>. */
  variant?: TypographyProps['variant']
  /** Keeps the <h1> for assistive technology but hides it visually (e.g. the chat page). */
  visuallyHidden?: boolean
  gutterBottom?: boolean
  sx?: TypographyProps['sx']
}

/**
 * The one <h1> of a page. Sets the document title and takes focus when it mounts after a route
 * change: AppShell moves focus to <main> on navigation, and a heading that renders later (async
 * data) pulls it from there so screen readers announce the new page by its title.
 */
export default function PageHeading({
  title,
  documentTitle,
  variant = 'h5',
  visuallyHidden: hidden = false,
  gutterBottom,
  sx,
}: PageHeadingProps) {
  const ref = useRef<HTMLHeadingElement>(null)
  usePageTitle(documentTitle ?? title)

  useEffect(() => {
    const main = document.getElementById(MAIN_CONTENT_ID)
    if (main && document.activeElement === main) {
      ref.current?.focus()
    }
  }, [])

  return (
    <Typography
      ref={ref}
      component="h1"
      variant={variant}
      tabIndex={-1}
      gutterBottom={gutterBottom}
      sx={hidden ? visuallyHidden : sx}
    >
      {title}
    </Typography>
  )
}
