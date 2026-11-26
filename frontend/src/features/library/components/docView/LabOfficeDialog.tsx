import { useEffect, useRef, useState, type ReactNode } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import GlobalStyles from '@mui/material/GlobalStyles'
import Typography from '@mui/material/Typography'
import CenterFocusStrongIcon from '@mui/icons-material/CenterFocusStrong'
import WarningAmberIcon from '@mui/icons-material/WarningAmber'
import { fontSize } from '@/shared/theme/tokens'
import { safeExternalHref } from '@/shared/utils/safeExternalHref'
import { downloadBlob } from '../../utils/documentContent'
import { passageRange } from '../../utils/textPassage'
import DownloadOriginalButton from '../DownloadOriginalButton'
import DocumentFrame from './DocumentFrame'
import { BRAND, deskColor, docViewVariant, type DocViewVariant } from './docViewVariant'
import FindingCard from './FindingCard'
import { parseCsv, parseMail, type OfficeOriginal, type ParsedMail } from './officeFormats'
import { labHighlightStyles, labPassageBlockSx, markPassageBlocks } from './textMarks'

type Variant = Exclude<DocViewVariant, null>
const TITLE_ID = 'office-original-title'
const HIGHLIGHT = 'opaa-office-passage'
const PAPER_SHADOW = '0 1px 2px rgba(16,24,40,0.08), 0 10px 28px rgba(36,40,85,0.14)'

/** DOCX, CSV and mail as originals: the page as written, the cited passage marked per variant. */
export default function LabOfficeDialog({
  original,
  onClose,
}: {
  original: OfficeOriginal | null
  onClose: () => void
}) {
  const variant = docViewVariant() ?? 'a'
  const scrollRef = useRef<HTMLDivElement>(null)
  // state, not a ref: the dialog mounts its content a render after opening
  const [body, setBody] = useState<HTMLDivElement | null>(null)
  const [found, setFound] = useState<{ of: OfficeOriginal; place: string | null } | 'missing'>()
  const [rendered, setRendered] = useState<OfficeOriginal | null>(null)

  // a Word file renders asynchronously into the body; CSV and mail are React content
  useEffect(() => {
    if (!original || original.format !== 'docx' || !body) return
    let cancelled = false
    body.replaceChildren()
    void import('docx-preview').then(async ({ renderAsync }) => {
      if (cancelled) return
      await renderAsync(original.blob, body, body, {
        className: 'docx',
        inWrapper: true,
        breakPages: true,
        ignoreLastRenderedPageBreak: true,
        renderAltChunks: false,
        renderComments: false,
        renderChanges: false,
        experimental: false,
        useBase64URL: true,
      })
      if (cancelled) return
      neutralizeLinks(body)
      setRendered(original)
    })
    return () => {
      cancelled = true
    }
  }, [original, body])

  const ready = original && (original.format !== 'docx' || rendered === original)
  useEffect(() => {
    const highlights = typeof CSS !== 'undefined' ? CSS.highlights : undefined
    if (!ready || !original || !body) return
    const anchor = original.anchor
    if (!anchor?.quoteStart) return
    // a frame later: the marks need the laid-out content, and the status is set outside the effect
    const frame = requestAnimationFrame(() => {
      const range = passageRange(body, anchor)
      if (!range) {
        setFound('missing')
        return
      }
      let place: string | null = anchor.path?.at(-1) ?? null
      if (original.format === 'csv') {
        const row = range.startContainer.parentElement?.closest('tr')
        body
          .querySelectorAll('[data-passage-row]')
          .forEach((r) => r.removeAttribute('data-passage-row'))
        row?.setAttribute('data-passage-row', '')
        if (row) place = `Zeile ${(row as HTMLTableRowElement).rowIndex + 1}`
      } else {
        if (highlights && typeof Highlight !== 'undefined')
          highlights.set(HIGHLIGHT, new Highlight(range))
        markPassageBlocks(body, range)
        if (original.format === 'mail') place = 'Nachricht'
      }
      setFound({ of: original, place })
      jumpTo(scrollRef.current, body)
    })
    return () => {
      cancelAnimationFrame(frame)
      highlights?.delete(HIGHLIGHT)
    }
  }, [ready, original, body])

  const hit = found !== 'missing' && found?.of === original ? found : null
  const status =
    found === 'missing'
      ? 'Die Fundstelle wurde im Dokument nicht gefunden.'
      : hit
        ? `Fundstelle markiert${hit.place ? ` · ${hit.place}` : ''}`
        : ''
  const onJump = hit ? () => jumpTo(scrollRef.current, body) : undefined
  const quote = [original?.anchor?.quoteStart, original?.anchor?.quoteEnd]
    .filter(Boolean)
    .join(' … ')

  return (
    <DocumentFrame
      open={original != null}
      onClose={onClose}
      titleId={TITLE_ID}
      title={original?.fileName}
      tall
      titleActions={
        original && (
          <DownloadOriginalButton
            onDownload={() => downloadBlob(original.blob, original.fileName)}
          />
        )
      }
      toolbar={
        variant !== 'c' && (
          <StatusBar status={status} warning={found === 'missing'} onJump={onJump} />
        )
      }
      contentSx={{ overflow: 'hidden' }}
    >
      <GlobalStyles
        styles={{
          [`::highlight(${HIGHLIGHT})`]: labHighlightStyles(variant),
          '@media (forced-colors: active)': {
            [`::highlight(${HIGHLIGHT})`]: { backgroundColor: 'Mark', color: 'MarkText' },
          },
        }}
      />
      <Box
        sx={(theme) => ({
          display: 'flex',
          flex: 1,
          minHeight: 0,
          flexDirection: { xs: 'column', sm: 'row' },
          bgcolor:
            variant === 'b' && original?.format !== 'docx'
              ? 'background.paper'
              : deskColor(variant, theme.palette.mode === 'dark'),
        })}
      >
        {variant === 'c' && (
          <FindingCard
            place={hit?.place ?? null}
            quote={quote}
            status={status}
            warning={found === 'missing'}
            onJump={onJump}
          />
        )}
        <Box
          ref={scrollRef}
          tabIndex={0}
          role="region"
          aria-label="Dokument"
          sx={{
            flex: 1,
            minWidth: 0,
            overflow: 'auto',
            px: { xs: 0, sm: 3 },
            py: { xs: 0, sm: 3 },
          }}
        >
          <Box
            ref={setBody}
            sx={[
              { '& [data-passage]': labPassageBlockSx(variant) },
              original?.format === 'docx' ? docxSx() : {},
            ]}
          >
            {original?.format === 'csv' && (
              <CsvTable text={original.text ?? ''} variant={variant} />
            )}
            {original?.format === 'mail' && (
              <MailView mail={parseMail(original.text ?? '')} variant={variant} />
            )}
          </Box>
        </Box>
      </Box>
    </DocumentFrame>
  )
}

/** Only http(s) links stay clickable and open apart from the app; every other target is dropped. */
function neutralizeLinks(root: HTMLElement) {
  root.querySelectorAll('a[href]').forEach((link) => {
    const href = link.getAttribute('href') ?? ''
    if (href.startsWith('#')) return
    const safe = safeExternalHref(href)
    if (!safe) {
      link.removeAttribute('href')
      return
    }
    link.setAttribute('href', safe)
    link.setAttribute('target', '_blank')
    link.setAttribute('rel', 'noopener noreferrer')
  })
}

function jumpTo(scroller: HTMLElement | null, body: HTMLElement | null) {
  const target = body?.querySelector<HTMLElement>('[data-passage], [data-passage-row]')
  if (!scroller || !target) return
  const top = target.getBoundingClientRect().top - scroller.getBoundingClientRect().top
  scroller.scrollTo({ top: scroller.scrollTop + top - scroller.clientHeight / 3 })
  if (!target.hasAttribute('tabindex')) target.setAttribute('tabindex', '-1')
  target.focus({ preventScroll: true })
}

function docxSx() {
  return {
    '& .docx-wrapper': {
      background: 'transparent',
      p: 0,
      display: 'flex',
      flexDirection: 'column',
      alignItems: 'center',
      gap: 3,
    },
    '& .docx-wrapper > section.docx': {
      boxShadow: PAPER_SHADOW,
      borderRadius: '2px',
      mb: 0,
      color: '#1d1f2e',
    },
    '@media (max-width: 599.95px)': {
      '& .docx-wrapper > section.docx': {
        width: '100% !important',
        minHeight: 'auto !important',
        padding: '16px !important',
        boxShadow: 'none',
      },
    },
  }
}

function StatusBar({
  status,
  warning,
  onJump,
}: {
  status: string
  warning: boolean
  onJump?: () => void
}) {
  return (
    <Box
      sx={{
        display: 'flex',
        alignItems: 'center',
        gap: 1,
        px: 2.5,
        py: 0.75,
        minHeight: 44,
        borderBottom: 1,
        borderColor: 'divider',
      }}
    >
      <Typography
        role="status"
        sx={{
          flex: 1,
          display: 'flex',
          alignItems: 'center',
          gap: 1,
          fontSize: fontSize.text,
          color: warning ? 'warning.main' : 'text.secondary',
          fontWeight: warning ? 600 : 400,
        }}
      >
        {warning && <WarningAmberIcon fontSize="small" />}
        {status}
      </Typography>
      {onJump && (
        <Button
          size="small"
          startIcon={<CenterFocusStrongIcon fontSize="small" />}
          onClick={onJump}
        >
          Zur Fundstelle
        </Button>
      )}
    </Box>
  )
}

function Paper({ variant, children }: { variant: Variant; children: ReactNode }) {
  if (variant === 'b') return <Box sx={{ px: { xs: 2, sm: 1 }, py: 1 }}>{children}</Box>
  return (
    <Box
      sx={{
        maxWidth: 1040,
        mx: 'auto',
        bgcolor: 'background.paper',
        color: 'text.primary',
        borderRadius: { xs: 0, sm: '4px' },
        boxShadow: { xs: 'none', sm: PAPER_SHADOW },
        px: { xs: 2, sm: 5 },
        py: { xs: 2, sm: 4 },
      }}
    >
      {children}
    </Box>
  )
}

function rowSx(variant: Variant) {
  if (variant === 'a')
    return {
      bgcolor: 'rgba(0, 157, 219, 0.18)',
      '& td:first-of-type': { boxShadow: `inset 4px 0 0 ${BRAND.blue}` },
    }
  if (variant === 'b')
    return {
      bgcolor: 'rgba(0, 157, 219, 0.10)',
      '& td': { borderBottom: `2px solid ${BRAND.blue}` },
    }
  return {
    bgcolor: 'rgba(0, 157, 219, 0.08)',
    outline: `2px solid ${BRAND.blue}`,
    outlineOffset: -2,
    '& td:first-of-type': { position: 'relative' },
    '& td:first-of-type::before': {
      content: '"Fundstelle"',
      position: 'absolute',
      left: 0,
      bottom: '100%',
      bgcolor: BRAND.blue,
      color: '#fff',
      font: '600 11px/16px Quicksand, Inter, sans-serif',
      px: 1,
      py: '1px',
      borderRadius: '4px 4px 0 0',
    },
  }
}

function CsvTable({ text, variant }: { text: string; variant: Variant }) {
  const [head, ...rows] = parseCsv(text)
  return (
    <Paper variant={variant}>
      <Box sx={{ overflowX: 'auto' }}>
        <Box
          component="table"
          sx={{
            borderCollapse: 'collapse',
            width: '100%',
            fontSize: fontSize.text,
            fontVariantNumeric: 'tabular-nums',
            '& th, & td': {
              textAlign: 'left',
              px: 1.5,
              py: 1,
              borderBottom: 1,
              borderColor: 'divider',
              whiteSpace: 'nowrap',
            },
            '& th': {
              position: 'sticky',
              top: 0,
              bgcolor: 'background.paper',
              fontWeight: 700,
              color: 'text.secondary',
              fontSize: fontSize.meta,
            },
            '& td.row-number, & th.row-number': {
              color: 'text.secondary',
              textAlign: 'right',
              pr: 1,
              width: 1,
            },
            '& tr[data-passage-row]': rowSx(variant),
          }}
        >
          <thead>
            <tr>
              <th className="row-number" scope="col">
                Zeile
              </th>
              {head?.map((cell, i) => (
                <th key={i} scope="col">
                  {cell}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row, r) => (
              <tr key={r}>
                <td className="row-number">{r + 2}</td>
                {row.map((cell, c) => (
                  <td key={c}>{cell}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </Box>
      </Box>
    </Paper>
  )
}

function MailView({ mail, variant }: { mail: ParsedMail; variant: Variant }) {
  const rows: [string, string | undefined][] = [
    ['Von', mail.from],
    ['An', mail.to],
    ['Cc', mail.cc],
    ['Datum', mail.date],
  ]
  return (
    <Paper variant={variant}>
      <Box sx={{ maxWidth: 760, mx: 'auto' }}>
        <Typography component="h3" sx={{ fontSize: fontSize.title, fontWeight: 600, mb: 1.5 }}>
          {mail.subject || '(ohne Betreff)'}
        </Typography>
        <Box
          component="dl"
          sx={{
            display: 'grid',
            gridTemplateColumns: 'max-content 1fr',
            columnGap: 2,
            rowGap: 0.5,
            m: 0,
            pb: 2,
            mb: 2,
            borderBottom: 1,
            borderColor: 'divider',
            fontSize: fontSize.text,
            '& dt': { color: 'text.secondary' },
            '& dd': { m: 0, overflowWrap: 'anywhere' },
          }}
        >
          {rows
            .filter(([, value]) => value)
            .map(([label, value]) => (
              <Box key={label} sx={{ display: 'contents' }}>
                <dt>{label}</dt>
                <dd>{value}</dd>
              </Box>
            ))}
        </Box>
        <Box sx={{ fontSize: fontSize.text, lineHeight: 1.7, '& p': { my: 1.25 } }}>
          {mail.body
            .split(/\n\s*\n/)
            .filter((p) => p.trim())
            .map((paragraph, i) => (
              <Box component="p" key={i} sx={{ whiteSpace: 'pre-line' }}>
                {paragraph}
              </Box>
            ))}
        </Box>
      </Box>
    </Paper>
  )
}
