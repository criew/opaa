import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { alpha } from '@mui/material/styles'
import { fontFamily } from '../../theme/tokens'
import { splitPromptText, type PromptTextSegment } from '../../utils/promptTemplate'

type MarkedKind = Exclude<PromptTextSegment['kind'], 'text'>

/**
 * Each kind of placeholder differs in colour and in a second visible feature: an own variable is
 * plain, a system variable dotted-underlined, an invalid placeholder wavy-underlined. The legend
 * below names the features; the title of each mark repeats its kind for assistive tech.
 */
const MARKS: Record<
  MarkedKind,
  { tone: 'primary' | 'info' | 'error'; title: string; decoration: string; legend: string }
> = {
  variable: { tone: 'primary', title: 'Variable', decoration: 'none', legend: 'Variable' },
  system: {
    tone: 'info',
    title: 'Systemvariable',
    decoration: 'underline dotted',
    legend: 'Systemvariable (gepunktet unterstrichen, füllt OPAA selbst)',
  },
  invalid: {
    tone: 'error',
    title: 'Ungültiger Platzhalter',
    decoration: 'underline wavy',
    legend: 'ungültiger Platzhalter (gewellt unterstrichen)',
  },
}

/** A prompt text with its placeholders marked, and a legend of the kinds that occur in it. */
export default function PromptTextHighlight({ text, label }: { text: string; label: string }) {
  const segments = splitPromptText(text)
  const kinds = (Object.keys(MARKS) as MarkedKind[]).filter((kind) =>
    segments.some((segment) => segment.kind === kind),
  )
  return (
    <Box>
      <Box
        role="region"
        aria-label={label}
        sx={{
          fontFamily: fontFamily.mono,
          fontSize: 12.5,
          lineHeight: 1.7,
          whiteSpace: 'pre-wrap',
          wordBreak: 'break-word',
          border: 1,
          borderColor: 'divider',
          borderRadius: '8px',
          px: 1.5,
          py: 1,
          bgcolor: 'background.default',
        }}
      >
        {segments.map((segment, index) => {
          if (segment.kind === 'text') return <span key={index}>{segment.value}</span>
          const mark = MARKS[segment.kind]
          return (
            <Box
              key={index}
              component="mark"
              title={mark.title}
              data-kind={segment.kind}
              sx={(theme) => ({
                color: theme.palette[mark.tone].main,
                bgcolor: alpha(theme.palette[mark.tone].main, 0.12),
                borderRadius: '4px',
                px: 0.25,
                textDecoration: mark.decoration,
                textUnderlineOffset: '3px',
              })}
            >
              {segment.value}
            </Box>
          )
        })}
      </Box>
      {kinds.length > 0 && (
        <Typography sx={{ fontSize: 11.5, color: 'text.secondary', mt: 0.5 }}>
          Hervorgehoben: {kinds.map((kind) => MARKS[kind].legend).join(' · ')}
        </Typography>
      )}
    </Box>
  )
}
