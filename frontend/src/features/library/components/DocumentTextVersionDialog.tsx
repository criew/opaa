import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import ButtonBase from '@mui/material/ButtonBase'
import CircularProgress from '@mui/material/CircularProgress'
import Dialog from '@mui/material/Dialog'
import DialogContent from '@mui/material/DialogContent'
import IconButton from '@mui/material/IconButton'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import useMediaQuery from '@mui/material/useMediaQuery'
import { useTheme } from '@mui/material/styles'
import visuallyHidden from '@mui/utils/visuallyHidden'
import TocIcon from '@mui/icons-material/Toc'
import type {
  ChunkAnchor,
  DocumentTextPassage,
  DocumentTextResponse,
  DocumentTextSection,
} from '@/shared/api/types'
import { notify } from '@/shared/stores/notificationStore'
import { markHighlight } from '@/shared/theme/tokens'
import DialogTitleWithClose from '@/shared/ui/DialogTitleWithClose'
import { getDocumentText } from '../api/libraryApi'
import { downloadDocumentContent } from '../utils/documentContent'
import DownloadOriginalButton from './DownloadOriginalButton'

/** A document whose Textfassung „Dokument öffnen“ shows, and the cited passage in it. */
export interface TextVersionPreview {
  documentId: string
  fileName: string
  /** The cited passage; without one the Textfassung opens at its beginning. */
  chunkIndex?: number | null
  /** The cited passage's anchor as the citation recorded it. */
  anchor?: ChunkAnchor | null
}

/** Passages per request - the server's own upper bound. */
const WINDOW = 40
/** Passages loaded ahead of the cited one, so it does not open at the very top. */
const LEAD = 5

const TITLE_ID = 'document-text-version-title'
const OUTLINE_ID = 'document-text-version-outline'

interface Loaded {
  of: TextVersionPreview
  first: number | null
  last: number | null
  passages: DocumentTextPassage[]
  sections: DocumentTextSection[]
  sectionsTruncated: boolean
}

type MarkState = 'none' | 'marked' | 'changed' | 'not-found'

interface DocumentTextVersionDialogProps {
  /** `null` keeps the dialog closed. */
  preview: TextVersionPreview | null
  onClose: () => void
}

/**
 * „Dokument öffnen“ for a format the browser cannot show (Office, spreadsheets, mail): the
 * document's indexed text in reading order, opened at the cited passage and marked as a whole -
 * the passage is the cited chunk itself, so no quote has to be searched. The text is loaded in
 * windows; the outline on the left reaches any section. The original stays one click away.
 */
export default function DocumentTextVersionDialog({
  preview,
  onClose,
}: DocumentTextVersionDialogProps) {
  const theme = useTheme()
  const wide = useMediaQuery(theme.breakpoints.up('md'))
  const [outlineToggled, setOutlineToggled] = useState<boolean | null>(null)
  const [loaded, setLoaded] = useState<Loaded | null>(null)
  const [failure, setFailure] = useState<{ of: TextVersionPreview; message: string } | null>(null)
  const [loadingMore, setLoadingMore] = useState<'before' | 'after' | 'jump' | null>(null)
  const scrollRef = useRef<HTMLDivElement | null>(null)
  // what the next render has to restore: a passage to bring into view, or the distance from the
  // bottom after text was put in front of the view
  const pendingScroll = useRef<{ chunkIndex: number; focus: boolean } | null>(null)
  const keepFromBottom = useRef<number | null>(null)

  useEffect(() => {
    if (!preview) return
    let cancelled = false
    const target = preview.chunkIndex
    const from = target != null ? Math.max(0, target - LEAD) : undefined
    const load = async (): Promise<DocumentTextResponse> => {
      const text = await getDocumentText(preview.documentId, {
        from,
        to: from != null ? from + WINDOW - 1 : undefined,
        sections: true,
      })
      // a passage beyond the current index (re-indexed since the citation) opens at the beginning
      if (text.passages.length === 0 && from != null && text.firstChunkIndex != null) {
        const start = await getDocumentText(preview.documentId, {})
        return { ...start, sections: text.sections, sectionsTruncated: text.sectionsTruncated }
      }
      return text
    }
    load()
      .then((text) => {
        if (cancelled) return
        pendingScroll.current = target != null ? { chunkIndex: target, focus: true } : null
        setLoaded({
          of: preview,
          first: text.firstChunkIndex ?? null,
          last: text.lastChunkIndex ?? null,
          passages: text.passages,
          sections: text.sections,
          sectionsTruncated: text.sectionsTruncated,
        })
      })
      .catch((err: unknown) => {
        if (cancelled) return
        setFailure({
          of: preview,
          message:
            err instanceof Error ? err.message : 'Die Textfassung konnte nicht geladen werden.',
        })
      })
    return () => {
      cancelled = true
    }
  }, [preview])

  const current = loaded?.of === preview ? loaded : null
  const failed = failure?.of === preview ? failure.message : null

  useLayoutEffect(() => {
    const container = scrollRef.current
    if (!container || !current) return
    if (keepFromBottom.current != null) {
      container.scrollTop = container.scrollHeight - keepFromBottom.current
      keepFromBottom.current = null
    }
    const pending = pendingScroll.current
    if (pending) {
      pendingScroll.current = null
      const block = container.querySelector<HTMLElement>(
        `[data-chunk-index="${pending.chunkIndex}"]`,
      )
      if (block && pending.focus) {
        block.scrollIntoView?.({ block: 'center' })
        block.focus({ preventScroll: true })
      } else if (block) {
        showSection(block)
      }
    }
  }, [current])

  async function loadWindow(
    range: { from: number; to: number },
    place: 'before' | 'after' | 'jump',
    scrollTo?: number,
  ) {
    if (!preview || !current) return
    setLoadingMore(place)
    try {
      const text = await getDocumentText(preview.documentId, range)
      const container = scrollRef.current
      if (place === 'before' && container) {
        keepFromBottom.current = container.scrollHeight - container.scrollTop
      }
      if (scrollTo != null) pendingScroll.current = { chunkIndex: scrollTo, focus: false }
      setLoaded((previous) => {
        if (!previous || previous.of !== preview) return previous
        const passages =
          place === 'jump'
            ? text.passages
            : place === 'before'
              ? [...text.passages, ...previous.passages]
              : [...previous.passages, ...text.passages]
        // an empty answer means a gap up to the end: nothing further lies in that direction
        const empty = text.passages.length === 0
        return {
          ...previous,
          passages,
          first: place === 'before' && empty ? previous.passages[0].chunkIndex : previous.first,
          last: place === 'after' && empty ? previous.passages.at(-1)!.chunkIndex : previous.last,
        }
      })
    } catch (err) {
      notify(
        err instanceof Error ? err.message : 'Die Textfassung konnte nicht geladen werden.',
        'error',
      )
    } finally {
      setLoadingMore(null)
    }
  }

  function goTo(chunkIndex: number) {
    const container = scrollRef.current
    const block = container?.querySelector<HTMLElement>(`[data-chunk-index="${chunkIndex}"]`)
    if (block) {
      showSection(block)
      return
    }
    void loadWindow({ from: chunkIndex, to: chunkIndex + WINDOW - 1 }, 'jump', chunkIndex)
  }

  const passages = current?.passages ?? []
  const firstLoaded = passages[0]?.chunkIndex
  const lastLoaded = passages.at(-1)?.chunkIndex
  const hasBefore = current?.first != null && firstLoaded != null && firstLoaded > current.first
  const hasAfter = current?.last != null && lastLoaded != null && lastLoaded < current.last
  const target = preview?.chunkIndex ?? null
  const markState = current ? passageMarkState(preview, current) : 'none'
  const sections = current?.sections ?? []
  const sectionsAt = new Map<number, DocumentTextSection[]>()
  for (const section of sections) {
    sectionsAt.set(section.chunkIndex, [...(sectionsAt.get(section.chunkIndex) ?? []), section])
  }
  const activeSection = target != null ? lastSectionAtOrBefore(sections, target) : null
  const outlineOpen = (outlineToggled ?? wide) && sections.length > 0

  return (
    <Dialog
      open={preview != null}
      onClose={onClose}
      maxWidth="lg"
      fullWidth
      aria-labelledby={TITLE_ID}
      slotProps={{ paper: { sx: { height: '90vh' } } }}
    >
      <DialogTitleWithClose
        id={TITLE_ID}
        onClose={onClose}
        actions={
          preview && (
            <DownloadOriginalButton
              onDownload={async () => {
                await downloadDocumentContent(preview.documentId, preview.fileName)
              }}
            />
          )
        }
      >
        <Typography component="span" sx={{ fontWeight: 600, wordBreak: 'break-word' }}>
          {preview?.fileName}
        </Typography>
      </DialogTitleWithClose>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, px: 3, pb: 1, flexWrap: 'wrap' }}>
        {sections.length > 0 && (
          <Tooltip title={outlineOpen ? 'Gliederung ausblenden' : 'Gliederung einblenden'}>
            <IconButton
              size="small"
              aria-label="Gliederung"
              aria-expanded={outlineOpen}
              aria-controls={outlineOpen ? OUTLINE_ID : undefined}
              onClick={() => setOutlineToggled(!outlineOpen)}
            >
              <TocIcon fontSize="small" />
            </IconButton>
          </Tooltip>
        )}
        <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
          Textfassung – das Layout des Originals wird nicht wiedergegeben.
        </Typography>
        <Typography
          role="status"
          sx={
            markState === 'changed' || markState === 'not-found'
              ? { fontSize: 13, color: 'warning.main', fontWeight: 600 }
              : visuallyHidden
          }
        >
          {statusText(markState)}
        </Typography>
      </Box>
      <DialogContent
        dividers
        sx={{ display: 'flex', flexDirection: { xs: 'column', md: 'row' }, minHeight: 0, p: 0 }}
      >
        {failed ? (
          <Alert severity="error" sx={{ m: 2, alignSelf: 'flex-start' }}>
            {failed}
          </Alert>
        ) : !current ? (
          <CircularProgress aria-label="Textfassung wird geladen" sx={{ m: 3 }} />
        ) : passages.length === 0 ? (
          <Typography sx={{ m: 3 }}>
            Für dieses Dokument liegt keine Textfassung vor. Das Original lässt sich oben
            herunterladen.
          </Typography>
        ) : (
          <>
            {outlineOpen && (
              <Box
                id={OUTLINE_ID}
                component="nav"
                aria-label="Gliederung"
                // beside the text where there is room, above it on a narrow screen
                sx={{
                  width: { md: 260 },
                  maxHeight: { xs: '35%', md: 'none' },
                  flex: 'none',
                  overflow: 'auto',
                  borderStyle: 'solid',
                  borderColor: 'divider',
                  borderWidth: { xs: '0 0 1px 0', md: '0 1px 0 0' },
                  p: 1,
                }}
              >
                <Box component="ul" sx={{ listStyle: 'none', m: 0, p: 0 }}>
                  {sections.map((section, index) => (
                    <Box component="li" key={index}>
                      <ButtonBase
                        onClick={() => goTo(section.chunkIndex)}
                        aria-current={section === activeSection ? 'location' : undefined}
                        sx={{
                          display: 'block',
                          width: '100%',
                          textAlign: 'left',
                          fontSize: 13,
                          fontWeight: section === activeSection ? 600 : 400,
                          bgcolor: section === activeSection ? 'action.selected' : undefined,
                          py: 0.5,
                          px: 0.5,
                          pl: 0.5 + (section.level - 1) * 1.5,
                          borderRadius: 1,
                          '&:hover': { bgcolor: 'action.hover' },
                        }}
                      >
                        {section.label}
                      </ButtonBase>
                    </Box>
                  ))}
                </Box>
                {current.sectionsTruncated && (
                  <Typography sx={{ fontSize: 12, color: 'text.secondary', mt: 1, px: 0.5 }}>
                    Die Gliederung ist gekürzt.
                  </Typography>
                )}
              </Box>
            )}
            <Box
              ref={scrollRef}
              data-testid="text-version-content"
              sx={{ flex: 1, minWidth: 0, overflow: 'auto', px: 3, py: 2 }}
            >
              {hasBefore && (
                <Button
                  size="small"
                  disabled={loadingMore != null}
                  onClick={() =>
                    void loadWindow(
                      {
                        from: Math.max(current.first ?? 0, firstLoaded! - WINDOW),
                        to: firstLoaded! - 1,
                      },
                      'before',
                    )
                  }
                  sx={{ mb: 2 }}
                >
                  {loadingMore === 'before' ? 'Lädt …' : 'Vorherigen Text laden'}
                </Button>
              )}
              {passages.map((passage) => (
                <Box key={passage.chunkIndex}>
                  {sectionsAt.get(passage.chunkIndex)?.map((section, index) => (
                    <Typography
                      key={index}
                      component={headingTag(section.level)}
                      sx={{
                        fontSize: section.level === 1 ? 15 : 14,
                        fontWeight: 600,
                        mt: 2,
                        mb: 1,
                      }}
                    >
                      {section.label}
                    </Typography>
                  ))}
                  <Box
                    data-chunk-index={passage.chunkIndex}
                    tabIndex={-1}
                    role={passage.chunkIndex === target ? 'region' : undefined}
                    aria-label={passage.chunkIndex === target ? 'Fundstelle' : undefined}
                    sx={{
                      whiteSpace: 'pre-wrap',
                      wordBreak: 'break-word',
                      fontSize: '0.9375rem',
                      lineHeight: 1.6,
                      mb: 1.5,
                      ...(passage.chunkIndex === target && {
                        bgcolor: markHighlight,
                        borderLeft: 3,
                        borderColor: 'warning.main',
                        pl: 1.5,
                        py: 0.5,
                        borderRadius: 1,
                        '@media (forced-colors: active)': {
                          backgroundColor: 'Mark',
                          color: 'MarkText',
                        },
                      }),
                    }}
                  >
                    {passage.text}
                  </Box>
                </Box>
              ))}
              {hasAfter && (
                <Button
                  size="small"
                  disabled={loadingMore != null}
                  onClick={() =>
                    void loadWindow({ from: lastLoaded! + 1, to: lastLoaded! + WINDOW }, 'after')
                  }
                >
                  {loadingMore === 'after' ? 'Lädt …' : 'Weiteren Text laden'}
                </Button>
              )}
            </Box>
          </>
        )}
      </DialogContent>
    </Dialog>
  )
}

/**
 * Whether the cited passage is loaded, and whether it still reads as cited: a passage whose
 * opening differs from the citation's was indexed anew since.
 */
function passageMarkState(preview: TextVersionPreview | null, loaded: Loaded): MarkState {
  const target = preview?.chunkIndex
  if (target == null) return 'none'
  const passage = loaded.passages.find((candidate) => candidate.chunkIndex === target)
  if (!passage) {
    // a passage merely outside the loaded window is still there; one the window or the index
    // bounds should hold is gone
    const firstLoaded = loaded.passages[0]?.chunkIndex
    const lastLoaded = loaded.passages.at(-1)?.chunkIndex
    const outsideIndex =
      loaded.first == null || loaded.last == null || target < loaded.first || target > loaded.last
    const insideWindow =
      firstLoaded != null && lastLoaded != null && target >= firstLoaded && target <= lastLoaded
    return outsideIndex || insideWindow ? 'not-found' : 'none'
  }
  const cited = preview?.anchor?.quoteStart
  const indexed = passage.anchor?.quoteStart
  if (cited && indexed && normalized(cited) !== normalized(indexed)) return 'changed'
  return 'marked'
}

function normalized(text: string): string {
  return text.replace(/\s+/g, ' ').trim()
}

function statusText(state: MarkState): string {
  switch (state) {
    case 'marked':
      return 'Fundstelle markiert.'
    case 'changed':
      return 'Das Dokument wurde seit dem Zitat neu indexiert – die markierte Stelle kann abweichen.'
    case 'not-found':
      return 'Die Fundstelle ist in der Textfassung nicht mehr enthalten.'
    default:
      return ''
  }
}

function lastSectionAtOrBefore(
  sections: DocumentTextSection[],
  chunkIndex: number,
): DocumentTextSection | null {
  let found: DocumentTextSection | null = null
  for (const section of sections) {
    if (section.chunkIndex > chunkIndex) break
    found = section
  }
  return found
}

/**
 * Brings a section into view with its heading, which sits above the passage in the same wrapper,
 * and moves focus there, so keyboard and screen reader continue in the section.
 */
function showSection(block: HTMLElement) {
  ;(block.parentElement ?? block).scrollIntoView?.({ block: 'start' })
  block.focus({ preventScroll: true })
}

/** Outline level 1 is the dialog's h3, below the h2 title; deeper levels follow, up to h6. */
function headingTag(level: number): 'h3' | 'h4' | 'h5' | 'h6' {
  return (['h3', 'h4', 'h5', 'h6'] as const)[Math.min(Math.max(level, 1), 4) - 1]
}
