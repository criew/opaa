import { useId, useState } from 'react'
import Box from '@mui/material/Box'
import ButtonBase from '@mui/material/ButtonBase'
import ListItemText from '@mui/material/ListItemText'
import Menu from '@mui/material/Menu'
import MenuItem from '@mui/material/MenuItem'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import { alpha } from '@mui/material/styles'
import type { Theme } from '@mui/material/styles'
import CheckIcon from '@mui/icons-material/Check'
import ContentCopyOutlinedIcon from '@mui/icons-material/ContentCopyOutlined'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import type { CitationIndex } from './citations'
import { answerAsMarkdown, answerAsPlainText, answerWithSources } from './copyFormats'
import { useClipboardCopy } from './useClipboardCopy'
import { focusRingAlpha, motion, radius } from '../../theme/tokens'

interface CopyAnswerButtonProps {
  content: string
  citations: CitationIndex
}

function segmentSx(theme: Theme) {
  const accent = theme.palette.primary.main
  return {
    height: '100%',
    color: 'text.secondary',
    transition: `background-color ${motion.durationFastMs}ms ${motion.easeOut}, color ${motion.durationFastMs}ms ${motion.easeOut}`,
    '&:hover': {
      color: 'text.primary',
      bgcolor: alpha(accent, theme.palette.mode === 'dark' ? 0.12 : 0.05),
    },
    '&.Mui-focusVisible': { boxShadow: `inset 0 0 0 2px ${alpha(accent, focusRingAlpha * 2)}` },
    '@media (prefers-reduced-motion: reduce)': { transition: 'none' },
  }
}

/**
 * Copies an answer: one click gives the Markdown without footnote marks, the arrow next to it
 * offers the answer with its sources as Markdown footnotes, or as plain text. A split pill in the
 * answer's foot, the same shape as "Belege anzeigen" but quieter - copying is the side action.
 */
export default function CopyAnswerButton({ content, citations }: CopyAnswerButtonProps) {
  const { copied, copy } = useClipboardCopy('Die Antwort')
  const [menuAnchor, setMenuAnchor] = useState<HTMLElement | null>(null)
  const menuId = useId()

  function copyVariant(text: string) {
    setMenuAnchor(null)
    void copy(text)
  }

  return (
    <Box
      role="group"
      aria-label="Antwort kopieren"
      sx={{
        display: 'inline-flex',
        alignItems: 'stretch',
        height: 30,
        borderRadius: `${radius.pill}px`,
        border: 1,
        borderColor: 'divider',
        overflow: 'hidden',
      }}
    >
      <Tooltip title={copied ? 'Kopiert' : 'Als Markdown kopieren, ohne Fußnoten'}>
        <ButtonBase
          aria-label={copied ? 'Antwort kopiert' : 'Antwort kopieren'}
          onClick={() => void copy(answerAsMarkdown(content))}
          sx={(theme) => ({ ...segmentSx(theme), gap: 0.75, pl: 1.25, pr: 1 })}
        >
          {copied ? (
            <CheckIcon aria-hidden sx={{ fontSize: 15, color: 'primary.main' }} />
          ) : (
            <ContentCopyOutlinedIcon aria-hidden sx={{ fontSize: 15 }} />
          )}
          <Typography component="span" sx={{ fontSize: 12.5, fontWeight: 500 }}>
            {copied ? 'Kopiert' : 'Kopieren'}
          </Typography>
        </ButtonBase>
      </Tooltip>
      <Box aria-hidden sx={{ width: '1px', my: 0.75, bgcolor: 'divider' }} />
      <Tooltip title="Weitere Kopieroptionen">
        <ButtonBase
          aria-label="Weitere Kopieroptionen"
          aria-haspopup="menu"
          aria-expanded={menuAnchor !== null}
          aria-controls={menuAnchor ? menuId : undefined}
          onClick={(event) => setMenuAnchor(event.currentTarget)}
          sx={(theme) => ({ ...segmentSx(theme), px: 0.5 })}
        >
          <ExpandMoreIcon aria-hidden sx={{ fontSize: 18 }} />
        </ButtonBase>
      </Tooltip>
      <Menu
        id={menuId}
        anchorEl={menuAnchor}
        open={menuAnchor !== null}
        onClose={() => setMenuAnchor(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
        transformOrigin={{ vertical: 'top', horizontal: 'right' }}
        slotProps={{ list: { 'aria-label': 'Kopieroptionen' } }}
      >
        <MenuItem onClick={() => copyVariant(answerWithSources(content, citations))}>
          <ListItemText
            primary="Mit Quellenangaben"
            secondary="Markdown mit Fußnoten und Quellenliste"
          />
        </MenuItem>
        <MenuItem onClick={() => copyVariant(answerAsPlainText(content))}>
          <ListItemText primary="Nur Text" secondary="Ohne Markdown-Formatierung" />
        </MenuItem>
      </Menu>
    </Box>
  )
}
