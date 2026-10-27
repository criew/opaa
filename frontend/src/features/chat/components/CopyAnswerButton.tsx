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
import type { CitationIndex } from '@/shared/ui/markdown/citations'
import { useBrandingStore } from '@/shared/branding/brandingStore'
import { useLinkTargetsStore } from '@/shared/linkTargets/linkTargetsStore'
import {
  answerAsMarkdown,
  answerAsPlainText,
  answerWithSources,
  copyNote,
  markdownWithNote,
  plainTextWithNote,
} from './copyFormats'
import { loadOptionalChunk } from '@/shared/utils/optionalChunk'
import { useClipboardCopy } from './useClipboardCopy'
import { motion, radius } from '@/shared/theme/tokens'

interface CopyAnswerButtonProps {
  content: string
  citations: CitationIndex
  /** The date the note names - when the answer was given. */
  answeredAt: Date
}

/**
 * The HTML flavour, loaded on demand. A missing chunk must not reload the page in the middle of
 * the copy, so the load is optional: it fails here and the copy falls back to plain text. A load
 * that answers without a module (a reload already running) fails the same way.
 */
async function htmlFor(
  markdown: string,
  note: string,
  allowedOrigins: readonly string[],
): Promise<string> {
  const module = await loadOptionalChunk(() => import('./copyHtml'))
  if (!module) throw new Error('HTML flavour unavailable')
  return module.answerAsHtml(markdown, note, allowedOrigins)
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
    '@media (prefers-reduced-motion: reduce)': { transition: 'none' },
  }
}

/**
 * Copies an answer: one click gives the Markdown without footnote marks, the arrow next to it
 * offers the answer with its sources as Markdown footnotes, or as plain text. A split pill in the
 * answer's foot, the same shape as "Belege anzeigen" but quieter - copying is the side action.
 */
export default function CopyAnswerButton({
  content,
  citations,
  answeredAt,
}: CopyAnswerButtonProps) {
  const { copied, copy } = useClipboardCopy('Die Antwort')
  const branding = useBrandingStore((s) => s.branding)
  const allowedOrigins = useLinkTargetsStore((s) => s.allowedOrigins)
  const [menuAnchor, setMenuAnchor] = useState<HTMLElement | null>(null)
  const menuId = useId()

  // Every format ends with the note; the two Markdown formats also travel as HTML. Text the
  // person marks by hand and copies themselves is not touched - there is no copy handler.
  function copyMarkdown(markdown: string) {
    const note = copyNote(branding.aiNotice, branding.productName, answeredAt)
    void copy(markdownWithNote(markdown, note), () => htmlFor(markdown, note, allowedOrigins))
  }

  function copyPlainText() {
    const note = copyNote(branding.aiNotice, branding.productName, answeredAt)
    void copy(plainTextWithNote(answerAsPlainText(content), note))
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
      }}
    >
      <Tooltip title={copied ? 'Kopiert' : 'Als Markdown kopieren, ohne Fußnoten'}>
        <ButtonBase
          aria-label={copied ? 'Antwort kopiert' : 'Antwort kopieren'}
          onClick={() => copyMarkdown(answerAsMarkdown(content))}
          sx={(theme) => ({
            ...segmentSx(theme),
            // The segments round off the pill themselves instead of a clipping container, so the
            // theme's focus ring stays whole around each of them.
            borderRadius: `${radius.pill}px 0 0 ${radius.pill}px`,
            gap: 0.75,
            pl: 1.25,
            pr: 1,
          })}
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
          sx={(theme) => ({
            ...segmentSx(theme),
            borderRadius: `0 ${radius.pill}px ${radius.pill}px 0`,
            px: 0.5,
          })}
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
        <MenuItem
          onClick={() => {
            setMenuAnchor(null)
            copyMarkdown(answerWithSources(content, citations))
          }}
        >
          <ListItemText
            primary="Mit Quellenangaben"
            secondary="Markdown mit Fußnoten und Quellenliste"
          />
        </MenuItem>
        <MenuItem
          onClick={() => {
            setMenuAnchor(null)
            copyPlainText()
          }}
        >
          <ListItemText primary="Nur Text" secondary="Ohne Markdown-Formatierung" />
        </MenuItem>
      </Menu>
    </Box>
  )
}
