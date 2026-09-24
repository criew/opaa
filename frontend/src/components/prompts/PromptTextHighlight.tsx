import Box from '@mui/material/Box'
import { alpha } from '@mui/material/styles'
import { fontFamily } from '../../theme/tokens'
import { splitPromptText } from '../../utils/promptTemplate'

/**
 * A prompt text with its placeholders marked: a defined variable in the accent colour, a system
 * variable in the info colour, an invalid `{{…}}` in the error colour. Each mark carries a title
 * naming its kind, so the colour is not the only signal.
 */
export default function PromptTextHighlight({ text, label }: { text: string; label: string }) {
  return (
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
      {splitPromptText(text).map((segment, index) => {
        if (segment.kind === 'text') return <span key={index}>{segment.value}</span>
        const tone =
          segment.kind === 'variable' ? 'primary' : segment.kind === 'system' ? 'info' : 'error'
        const title =
          segment.kind === 'variable'
            ? 'Variable'
            : segment.kind === 'system'
              ? 'Systemvariable'
              : 'Ungültiger Platzhalter'
        return (
          <Box
            key={index}
            component="mark"
            title={title}
            data-kind={segment.kind}
            sx={(theme) => ({
              color: theme.palette[tone].main,
              bgcolor: alpha(theme.palette[tone].main, 0.12),
              borderRadius: '4px',
              px: 0.25,
              textDecoration: segment.kind === 'invalid' ? 'wavy underline' : 'none',
            })}
          >
            {segment.value}
          </Box>
        )
      })}
    </Box>
  )
}
