import type { ReactNode, Ref } from 'react'
import Box from '@mui/material/Box'
import type { SxProps, Theme } from '@mui/material/styles'
import LightSurface from '@/shared/ui/LightSurface'
import { contentWidth, radius, shadow } from '@/shared/theme/tokens'

/**
 * A sheet of paper on the desk of the reading mode: light in both schemes, with the light roles
 * inside, so text and marks on it keep their contrast. Positioned, so a label can be placed on it.
 */
export default function DocumentPaper({
  wide = false,
  paperRef,
  sx,
  children,
}: {
  /** A table takes the width of a list page, running text that of a detail page. */
  wide?: boolean
  paperRef?: Ref<HTMLDivElement>
  sx?: SxProps<Theme>
  children: ReactNode
}) {
  return (
    <LightSurface>
      <Box
        ref={paperRef}
        data-document-paper=""
        sx={[
          {
            position: 'relative',
            maxWidth: wide ? contentWidth.list : contentWidth.detail,
            width: '100%',
            mx: 'auto',
            bgcolor: 'documentView.paper',
            color: 'text.primary',
            border: { xs: 0, sm: 1 },
            borderColor: { sm: 'divider' },
            borderRadius: { xs: 0, sm: `${radius.xs}px` },
            boxShadow: { xs: 'none', sm: shadow.floating },
            // the wider left margin holds the label „Fundstelle“ beside the passage
            pl: { xs: 2, sm: 13 },
            pr: { xs: 2, sm: 6 },
            py: { xs: 2, sm: 5 },
          },
          ...(Array.isArray(sx) ? sx : [sx]),
        ]}
      >
        {children}
      </Box>
    </LightSurface>
  )
}
