import type { ReactNode } from 'react'
import { Link as RouterLink } from 'react-router'
import Box from '@mui/material/Box'
import Link from '@mui/material/Link'
import Typography from '@mui/material/Typography'
import { TAB_CONTENT_WIDTH } from '@/shared/ui/TabSection'
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
  /** The one addition (a date): after the value from 600 px on, on its own line below it. */
  detail?: string | null
  /** Plain text of the value, for the tooltip of a value cut at the field's edge. */
  title?: string
  link?: BandLink | null
}

/** The state word with its dot; the signal colour stays in the dot, the word in `fg-1`. */
export function BandState({ tone, word }: { tone: StatusTone; word: string }) {
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
    </>
  )
}

/**
 * Below 600 px the link shrinks to its chevron and stretches over the whole field, so the field is
 * one line and still one tap target; its name stays for assistive tech.
 */
function FieldLink({ link }: { link: BandLink }) {
  const sx = {
    fontSize: 12.5,
    fontWeight: 500,
    whiteSpace: 'nowrap',
    '& .band-link-text': { display: { xs: 'none', sm: 'inline' } },
    '&::after': { content: { xs: '""', sm: 'none' }, position: 'absolute', inset: 0 },
  } as const
  const label = link.label.replace(/ ›$/, '')
  const content = (
    <>
      <span className="band-link-text">{label}</span>
      {' ›'}
    </>
  )
  if ('to' in link) {
    return (
      <Link component={RouterLink} to={link.to} underline="hover" aria-label={label} sx={sx}>
        {content}
      </Link>
    )
  }
  return (
    <Link
      component="button"
      type="button"
      underline="hover"
      onClick={link.onClick}
      aria-label={label}
      sx={sx}
    >
      {content}
    </Link>
  )
}

/** The one content width of the page: band, run table and every tab end at the same edge. */
const BAND_WIDTH = TAB_CONTENT_WIDTH

/**
 * The status band under the title: three fields that never move and look the same for every
 * source type - only their text differs. A hairline above and between them, no frame, no tint,
 * no icons. Each field holds its label with the way to the details beside it and one line of
 * value with its addition. Below 600 px the fields stack as one line each; the first field keeps
 * a second line for its addition, so the state word and its date are never cut.
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
            pr: { xs: field.link ? 2 : 0, sm: 2 },
            py: { xs: 0.5, sm: 1 },
            borderStyle: 'solid',
            borderColor: 'divider',
            borderWidth: 0,
            borderLeftWidth: { xs: 0, sm: index > 0 ? 1 : 0 },
            borderTopWidth: { xs: index > 0 ? 1 : 0, sm: 0 },
            display: 'grid',
            // below 600 px the chevron sits on the right edge of the stretched link, outside the grid
            gridTemplateColumns: { xs: '76px minmax(0, 1fr)', sm: 'minmax(0, 1fr) auto' },
            gridTemplateAreas: {
              xs: index === 0 ? '"label value" ". detail"' : '"label value"',
              sm: '"label link" "value value"',
            },
            gridTemplateRows: { xs: index === 0 ? '22px 20px' : '22px', sm: '20px 22px' },
            columnGap: 1.5,
            alignItems: 'baseline',
          }}
        >
          <Typography
            component="dt"
            sx={{
              gridArea: 'label',
              fontSize: { xs: 11.5, sm: 12 },
              fontWeight: 500,
              color: 'text.secondary',
              lineHeight: '20px',
            }}
          >
            {field.label}
          </Typography>
          <Typography
            component="dd"
            title={field.title}
            sx={{
              gridArea: 'value',
              m: 0,
              fontSize: { xs: 13, sm: 13.5 },
              lineHeight: '22px',
              color: 'text.primary',
              whiteSpace: 'nowrap',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
            }}
          >
            {field.value}
            {field.detail && (
              <Box
                component="span"
                sx={{
                  color: 'text.secondary',
                  display: { xs: index === 0 ? 'none' : 'inline', sm: 'inline' },
                }}
              >
                {` · ${field.detail}`}
              </Box>
            )}
          </Typography>
          {index === 0 && (
            <Typography
              component="dd"
              aria-hidden
              sx={{
                gridArea: 'detail',
                display: { xs: 'block', sm: 'none' },
                m: 0,
                fontSize: 13,
                lineHeight: '20px',
                color: 'text.secondary',
                whiteSpace: 'nowrap',
              }}
            >
              {field.detail}
            </Typography>
          )}
          <Box
            component="dd"
            sx={{
              gridArea: { sm: 'link' },
              m: 0,
              lineHeight: '20px',
              position: { xs: 'absolute', sm: 'static' },
              right: 0,
              top: 4,
            }}
          >
            {field.link && <FieldLink link={field.link} />}
          </Box>
        </Box>
      ))}
    </Box>
  )
}
