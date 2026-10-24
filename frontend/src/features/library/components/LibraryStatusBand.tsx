import type { ReactNode } from 'react'
import { Link as RouterLink } from 'react-router'
import Box from '@mui/material/Box'
import Link from '@mui/material/Link'
import Typography from '@mui/material/Typography'
import type { StatusTone } from '@/shared/ui/StatusLine'

const DOT_COLOR: Record<StatusTone, string> = {
  neutral: 'text.disabled',
  success: 'success.main',
  warning: 'warning.main',
  error: 'error.main',
}

/** A field's way to the place behind it: a tab of the page, or an action in place. */
export type BandLink =
  { label: string; to: string; describedBy?: string } | { label: string; onClick: () => void }

export interface BandField {
  label: string
  value: ReactNode
  /** Plain text of the value, for the tooltip of a value cut at the field's edge. */
  title?: string
  link?: BandLink | null
  /** 0–100: a thin bar inside the field, never across the page. */
  progress?: number | null
}

/** The state word with its dot; the signal colour stays in the dot, the word in `fg-1`. */
export function BandState({
  tone,
  word,
  extra,
}: {
  tone: StatusTone
  word: string
  extra?: string | null
}) {
  return (
    <>
      <Box
        component="span"
        aria-hidden
        sx={{
          display: 'inline-block',
          width: 8,
          height: 8,
          borderRadius: '50%',
          bgcolor: DOT_COLOR[tone],
          mr: 0.75,
          verticalAlign: 'middle',
          position: 'relative',
          top: -1,
        }}
      />
      <Box component="span" sx={{ fontWeight: 600 }}>
        {word}
      </Box>
      {extra && (
        <Box component="span" sx={{ color: 'text.secondary' }}>
          {` · ${extra}`}
        </Box>
      )}
    </>
  )
}

function FieldLink({ link }: { link: BandLink }) {
  const sx = { fontSize: 12.5, fontWeight: 500, whiteSpace: 'nowrap' } as const
  if ('to' in link) {
    return (
      <Link component={RouterLink} to={link.to} underline="hover" sx={sx}>
        {link.label}
      </Link>
    )
  }
  return (
    <Link component="button" type="button" underline="hover" onClick={link.onClick} sx={sx}>
      {link.label}
    </Link>
  )
}

/** As wide as the run table below it; three equal fields of about 290 px at 1280 px. */
const BAND_WIDTH = 880

/**
 * The status band under the title: three fields that never move and look the same for every
 * source type - only their text differs. A hairline above and between them, no frame, no
 * tint, no icons. Each field holds
 * its label, one line of value and one line for the way to the details, reserved even when
 * empty, so the tab bar below sits at the same height in every state. Below 600 px the fields
 * stack as one line each.
 */
export default function LibraryStatusBand({
  fields,
  label,
}: {
  fields: [BandField, BandField, BandField]
  label: string
}) {
  return (
    <Box
      component="dl"
      aria-label={label}
      sx={{
        m: 0,
        mt: 1.5,
        maxWidth: BAND_WIDTH,
        display: 'grid',
        gridTemplateColumns: { xs: '1fr', sm: 'repeat(3, minmax(0, 1fr))' },
        // a hairline above and between the fields, no frame around them: a strip, not a card
        borderTop: '1px solid',
        borderColor: 'divider',
      }}
    >
      {fields.map((field, index) => (
        <Box
          key={field.label}
          sx={{
            position: 'relative',
            minWidth: 0,
            pl: { xs: 0, sm: index > 0 ? 2 : 0 },
            pr: { xs: 0, sm: 2 },
            py: { xs: 1, sm: 1.25 },
            borderStyle: 'solid',
            borderColor: 'divider',
            borderWidth: 0,
            borderLeftWidth: { xs: 0, sm: index > 0 ? 1 : 0 },
            borderTopWidth: { xs: index > 0 ? 1 : 0, sm: 0 },
            display: { xs: 'grid', sm: 'block' },
            gridTemplateColumns: { xs: '92px minmax(0, 1fr) auto' },
            columnGap: 1.5,
            alignItems: 'baseline',
          }}
        >
          <Typography
            component="dt"
            sx={{ fontSize: 12, fontWeight: 500, color: 'text.secondary', lineHeight: '18px' }}
          >
            {field.label}
          </Typography>
          <Typography
            component="dd"
            title={field.title}
            sx={{
              m: 0,
              mt: { xs: 0, sm: 0.25 },
              fontSize: 13.5,
              lineHeight: '22px',
              color: 'text.primary',
              whiteSpace: 'nowrap',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
            }}
          >
            {field.value}
          </Typography>
          <Box
            component="dd"
            sx={{ m: 0, minHeight: { xs: 0, sm: 20 }, lineHeight: '20px', textAlign: 'left' }}
          >
            {field.link && <FieldLink link={field.link} />}
          </Box>
          {field.progress != null && (
            <Box
              role="progressbar"
              aria-label="Fortschritt des Laufs"
              aria-valuemin={0}
              aria-valuemax={100}
              aria-valuenow={field.progress}
              sx={{
                position: 'absolute',
                left: 0,
                right: 0,
                bottom: 0,
                height: 2,
                bgcolor: 'divider',
              }}
            >
              <Box
                sx={{
                  height: '100%',
                  width: `${field.progress}%`,
                  bgcolor: 'text.secondary',
                  transition: 'width 200ms cubic-bezier(0.22, 1, 0.36, 1)',
                }}
              />
            </Box>
          )}
        </Box>
      ))}
    </Box>
  )
}
