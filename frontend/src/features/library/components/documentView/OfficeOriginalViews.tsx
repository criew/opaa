import { useEffect, useEffectEvent, useRef } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { useTheme } from '@mui/material/styles'
import { fontSize, fontWeight, shadow } from '@/shared/theme/tokens'
import LightSurface from '@/shared/ui/LightSurface'
import type { CsvTable } from '../../utils/csvTable'
import { neutralizeLinks } from '../../utils/documentLinks'
import type { MailMessage } from '../../utils/mailMessage'
import { passageLabelSx } from './styles'
import { PASSAGE_BLOCK_ATTR, passageBandSx } from './textMarks'

/** Attribute on the table row of a CSV original that holds the cited passage. */
export const PASSAGE_ROW_ATTR = 'data-passage-row'

/**
 * A CSV original as a table with its record numbers; the rows in `marked` carry the band, the
 * first of them the label „Fundstelle“ in its number cell, where it covers no other row.
 */
export function CsvTableView({ table, marked }: { table: CsvTable; marked: ReadonlySet<number> }) {
  const first = marked.size > 0 ? Math.min(...marked) : null
  return (
    <Box role="region" aria-label="Tabelle" tabIndex={0} sx={{ overflowX: 'auto' }}>
      <Box
        component="table"
        sx={{
          borderCollapse: 'collapse',
          width: '100%',
          fontSize: fontSize.text,
          fontVariantNumeric: 'tabular-nums',
          '& th, & td': {
            textAlign: 'left',
            verticalAlign: 'top',
            px: 1.5,
            py: 1,
            borderBottom: 1,
            borderColor: 'divider',
            whiteSpace: 'pre-wrap',
          },
          '& th': {
            fontWeight: fontWeight.medium,
            color: 'text.secondary',
            fontSize: fontSize.meta,
          },
          '& .row-number': { color: 'text.secondary', textAlign: 'right', width: '1%' },
          [`& tr[${PASSAGE_ROW_ATTR}] > td`]: { bgcolor: 'documentView.band' },
          [`& tr[${PASSAGE_ROW_ATTR}] > td:first-of-type`]: passageBandSx,
        }}
      >
        <thead>
          <tr>
            <th className="row-number" scope="col">
              Zeile
            </th>
            {table.head.map((cell, index) => (
              <th key={index} scope="col">
                {cell}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {table.rows.map((row) => (
            <tr
              key={row.number}
              data-row={row.number}
              {...(marked.has(row.number) ? { [PASSAGE_ROW_ATTR]: '' } : {})}
            >
              <td className="row-number">
                {row.number === first && (
                  <Box
                    component="span"
                    aria-hidden="true"
                    data-testid="passage-label"
                    sx={{ ...passageLabelSx, position: 'static', display: 'block', mb: 0.5 }}
                  >
                    Fundstelle
                  </Box>
                )}
                {row.number}
              </td>
              {row.cells.map((cell, index) => (
                <td key={index}>{cell}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </Box>
    </Box>
  )
}

/** A mail original: subject, head lines and the plain text as paragraphs. */
export function MailView({ mail }: { mail: MailMessage }) {
  const head: [string, string | undefined][] = [
    ['Von', mail.from],
    ['An', mail.to],
    ['Cc', mail.cc],
    ['Datum', mail.date],
  ]
  return (
    <Box>
      <Typography
        component="h3"
        sx={{ fontSize: fontSize.title, fontWeight: fontWeight.semibold, mb: 1.5 }}
      >
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
        {head
          .filter(([, value]) => value)
          .map(([label, value]) => (
            <Box key={label} sx={{ display: 'contents' }}>
              <dt>{label}</dt>
              <dd>{value}</dd>
            </Box>
          ))}
      </Box>
      <Box data-mail-body="" sx={{ fontSize: fontSize.title, lineHeight: 1.6 }}>
        {mail.paragraphs.map((paragraph, index) => (
          <Box
            component="p"
            key={index}
            sx={{ m: 0, mb: 1.5, whiteSpace: 'pre-line', overflowWrap: 'anywhere' }}
          >
            {paragraph}
          </Box>
        ))}
      </Box>
    </Box>
  )
}

/** The options of docx-preview: no embedded HTML, comments, tracked changes or fonts. */
const DOCX_RENDER_OPTIONS = {
  className: 'docx',
  inWrapper: true,
  breakPages: true,
  ignoreLastRenderedPageBreak: true,
  ignoreFonts: true,
  renderHeaders: true,
  renderFooters: true,
  renderFootnotes: true,
  renderEndnotes: true,
  renderAltChunks: false,
  renderComments: false,
  renderChanges: false,
  experimental: false,
  useBase64URL: true,
  debug: false,
} as const

/**
 * A Word original rendered by docx-preview into a shadow root of its own, so the document's
 * styles cannot reach the app and the app's cannot reach the document. Links are made safe before
 * `onRendered` hands over the body to search and mark; a file it cannot read calls `onFailed`.
 */
export function DocxView({
  blob,
  highlight,
  onRendered,
  onFailed,
}: {
  blob: Blob
  /** Name of the CSS Custom Highlight the marks use. */
  highlight: string
  onRendered: (body: HTMLElement) => void
  onFailed: () => void
}) {
  return (
    <LightSurface>
      <DocxShadow blob={blob} highlight={highlight} onRendered={onRendered} onFailed={onFailed} />
    </LightSurface>
  )
}

function DocxShadow({
  blob,
  highlight,
  onRendered,
  onFailed,
}: {
  blob: Blob
  highlight: string
  onRendered: (body: HTMLElement) => void
  onFailed: () => void
}) {
  const hostRef = useRef<HTMLDivElement>(null)
  const rendered = useEffectEvent((body: HTMLElement) => onRendered(body))
  const failed = useEffectEvent(() => onFailed())
  const theme = useTheme()
  const { band, edge } = theme.palette.documentView
  const text = theme.palette.text.primary
  const css = `
    :host { display: block; color: ${text}; }
    .docx-wrapper { background: transparent !important; padding: 0 !important;
      display: flex; flex-direction: column; align-items: center; gap: 24px; }
    .docx-wrapper > section.docx { box-shadow: ${shadow.floating}; margin-bottom: 0 !important;
      color: ${text}; }
    ::highlight(${highlight}) { background-color: ${band}; }
    [${PASSAGE_BLOCK_ATTR}] { position: relative; }
    [${PASSAGE_BLOCK_ATTR}]::before { content: ''; position: absolute; left: -12px; top: 0;
      bottom: 0; width: 4px; border-radius: 2px; background: ${edge}; }
    @media (max-width: 599.95px) {
      .docx-wrapper > section.docx { width: 100% !important; min-height: auto !important;
        padding: 16px !important; box-shadow: none; }
    }
    @media (forced-colors: active) {
      ::highlight(${highlight}) { background-color: Mark; color: MarkText; }
      [${PASSAGE_BLOCK_ATTR}]::before { background: Highlight; }
    }`

  useEffect(() => {
    const host = hostRef.current
    if (!host) return
    let cancelled = false
    const root = host.shadowRoot ?? host.attachShadow({ mode: 'open' })
    const own = host.ownerDocument.createElement('style')
    own.textContent = css
    const styles = host.ownerDocument.createElement('div')
    const body = host.ownerDocument.createElement('div')
    root.replaceChildren(own, styles, body)
    // links are made safe as soon as they are inserted, before any event can reach them
    const guard =
      typeof MutationObserver !== 'undefined'
        ? new MutationObserver(() => neutralizeLinks(body))
        : undefined
    guard?.observe(body, { childList: true, subtree: true })
    import('docx-preview')
      .then(({ renderAsync }) => renderAsync(blob, body, styles, DOCX_RENDER_OPTIONS))
      .then(() => {
        if (cancelled) return
        neutralizeLinks(body)
        rendered(body)
      })
      .catch(() => {
        if (!cancelled) failed()
      })
    return () => {
      cancelled = true
      guard?.disconnect()
      root.replaceChildren()
    }
  }, [blob, css])

  return <Box ref={hostRef} data-testid="docx-original" />
}
