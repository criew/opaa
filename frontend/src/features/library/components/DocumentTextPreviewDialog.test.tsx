import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, onTestFinished, vi } from 'vitest'
import { renderWithProviders } from '../../../test/test-utils'
import DocumentTextPreviewDialog from './DocumentTextPreviewDialog'
import type { TextPreviewResult } from '../utils/documentContent'

function markdownDoc(content: string, fileName = '001_personalausweis.md'): TextPreviewResult {
  return {
    kind: 'text-preview',
    fileName,
    contentType: 'text/markdown',
    content,
    blob: new Blob([content]),
  }
}

function plainTextDoc(content: string, fileName = 'notiz.txt'): TextPreviewResult {
  return {
    kind: 'text-preview',
    fileName,
    contentType: 'text/plain',
    content,
    blob: new Blob([content]),
  }
}

describe('DocumentTextPreviewDialog', () => {
  it('stays closed when no document is given', () => {
    renderWithProviders(<DocumentTextPreviewDialog previewDocument={null} onClose={vi.fn()} />)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('shows the file name and renders Markdown content readable in the browser', () => {
    renderWithProviders(
      <DocumentTextPreviewDialog
        previewDocument={markdownDoc('# Titel\n\nEin Absatz.')}
        onClose={vi.fn()}
      />,
    )
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByText('001_personalausweis.md')).toBeInTheDocument()
    // Heading elements are normalised per rendered content (rank compression from h2);
    // the h5 LOOK of "#" survives as the Typography variant.
    const heading1016 = screen.getByText('Titel').closest('h2')
    expect(heading1016).toBeInTheDocument()
    expect(heading1016).toHaveClass('MuiTypography-h5')
    expect(screen.getByText('Ein Absatz.')).toBeInTheDocument()
  })

  // MUI does not wire aria-labelledby to DialogTitle automatically.
  it('labels the dialog with the file name via aria-labelledby', () => {
    renderWithProviders(
      <DocumentTextPreviewDialog previewDocument={markdownDoc('Inhalt')} onClose={vi.fn()} />,
    )
    const dialog = screen.getByRole('dialog')
    const labelledBy = dialog.getAttribute('aria-labelledby')
    expect(labelledBy).toBeTruthy()
    expect(document.getElementById(labelledBy!)).toHaveTextContent('001_personalausweis.md')
  })

  it('renders plain text as-is, preserving line breaks', () => {
    renderWithProviders(
      <DocumentTextPreviewDialog
        previewDocument={plainTextDoc('Zeile 1\nZeile 2')}
        onClose={vi.fn()}
      />,
    )
    const pre = document.querySelector('pre')
    expect(pre).toBeInTheDocument()
    expect(pre?.textContent).toBe('Zeile 1\nZeile 2')
  })

  it('fires onClose when the close button is clicked', async () => {
    const onClose = vi.fn()
    const user = userEvent.setup()
    renderWithProviders(
      <DocumentTextPreviewDialog previewDocument={markdownDoc('Inhalt')} onClose={onClose} />,
    )
    await user.click(screen.getByRole('button', { name: 'Schließen' }))
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  // A document preview's content is not a chat answer - MarkdownRenderer's
  // default citation-marker stripping must not silently mutate a coincidental 【…】 run that is
  // part of the original text.
  it('shows a raw 【source: …】-shaped run in the original instead of silently stripping it', () => {
    renderWithProviders(
      <DocumentTextPreviewDialog
        previewDocument={markdownDoc('Vorher 【source: doc-1#0 | irrelevant.md】 nachher.')}
        onClose={vi.fn()}
      />,
    )
    expect(
      screen.getByText('Vorher 【source: doc-1#0 | irrelevant.md】 nachher.'),
    ).toBeInTheDocument()
  })

  // No gerendertes Markdown may execute script in the app's origin - a
  // literal <script>/onerror-carrying <img> is inert text (react-markdown never enables rehype-raw,
  // so raw HTML is parsed as text, not markup), and a javascript: link/image URL stays text because
  // MarkdownRenderer's own link and image components never put it into an href or src.
  describe('security: no script execution from rendered Markdown (#780)', () => {
    it('renders a literal <script> tag as inert text, not a script element', () => {
      renderWithProviders(
        <DocumentTextPreviewDialog
          previewDocument={markdownDoc(
            'Vor dem Angriff.\n\n<script>window.__pwned = true</script>\n\nNach dem Angriff.',
          )}
          onClose={vi.fn()}
        />,
      )
      expect(document.querySelector('script')).not.toBeInTheDocument()
      expect((window as unknown as { __pwned?: boolean }).__pwned).toBeUndefined()
    })

    it('renders a literal <img onerror=...> as inert text, not an executable element', () => {
      renderWithProviders(
        <DocumentTextPreviewDialog
          previewDocument={markdownDoc('<img src=x onerror="window.__pwned = true">')}
          onClose={vi.fn()}
        />,
      )
      const img = document.querySelector('img')
      expect(img).not.toBeInTheDocument()
      expect((window as unknown as { __pwned?: boolean }).__pwned).toBeUndefined()
    })

    it('shows a javascript: link as text instead of rendering it as href', () => {
      renderWithProviders(
        <DocumentTextPreviewDialog
          previewDocument={markdownDoc("[Klick mich](javascript:alert('x'))")}
          onClose={vi.fn()}
        />,
      )
      expect(screen.getByText(/Klick mich/).closest('a')).toBeNull()
      expect(document.querySelector('a[href^="javascript:"]')).toBeNull()
    })

    it('never renders an image, a javascript: one included', () => {
      renderWithProviders(
        <DocumentTextPreviewDialog
          previewDocument={markdownDoc("![Bild](javascript:alert('x'))")}
          onClose={vi.fn()}
        />,
      )
      expect(document.querySelector('img')).toBeNull()
      expect(screen.getByText(/\[Bild: Bild\]/)).toBeInTheDocument()
    })
  })

  describe('cited passage', () => {
    const highlights = new Map<string, unknown>()

    beforeEach(() => {
      highlights.clear()
      vi.stubGlobal('CSS', { highlights })
      vi.stubGlobal(
        'Highlight',
        class {
          ranges: Range[]
          constructor(...ranges: Range[]) {
            this.ranges = ranges
          }
        },
      )
    })

    afterEach(() => {
      vi.unstubAllGlobals()
      vi.restoreAllMocks()
    })

    it('marks the cited passage as a highlight and says so', async () => {
      const doc = {
        ...markdownDoc('# Fristen\n\nDie Frist beginnt mit der **Zustellung**.\n\nDanach nichts.'),
        anchor: { quoteStart: 'Die Frist beginnt mit der **Zustellung**.' },
      }
      renderWithProviders(<DocumentTextPreviewDialog previewDocument={doc} onClose={vi.fn()} />)

      expect(await screen.findByRole('status')).toHaveTextContent('Fundstelle markiert.')
      const highlight = highlights.get('opaa-passage') as { ranges: Range[] }
      expect(highlight.ranges[0].toString()).toBe('Die Frist beginnt mit der Zustellung')
    })

    it('says so when the passage is not in the document', async () => {
      const doc = { ...plainTextDoc('Ganz anderer Inhalt.'), anchor: { quoteStart: 'Fehlt' } }
      renderWithProviders(<DocumentTextPreviewDialog previewDocument={doc} onClose={vi.fn()} />)

      expect(await screen.findByRole('status')).toHaveTextContent(
        'Die Fundstelle wurde im Dokument nicht gefunden.',
      )
      expect(highlights.size).toBe(0)
    })

    it('finds the passage in rendered Markdown lists, tables and links', async () => {
      const doc = {
        ...markdownDoc(
          'Vorab.\n\n1. Antrag stellen\n2. Frist abwarten\n\n| Stelle | Frist |\n|---|---|\n| Amt | 2 Wochen |\n\n' +
            'Siehe [Handbuch](https://extern.example/h).',
        ),
        anchor: {
          quoteStart: '1. Antrag stellen 2. Frist abwarten | Stelle | Frist | |---|---| | Amt',
          quoteEnd: '| 2 Wochen | Siehe [Handbuch](https://extern.example/h).',
        },
      }
      renderWithProviders(<DocumentTextPreviewDialog previewDocument={doc} onClose={vi.fn()} />)

      expect(await screen.findByRole('status')).toHaveTextContent('Fundstelle markiert.')
      const highlight = highlights.get('opaa-passage') as { ranges: Range[] }
      expect(highlight.ranges[0].toString()).toMatch(/^Antrag stellen.*Handbuch/s)
    })

    it('scrolls the passage itself into view and focuses its block', async () => {
      const text = `${'Zeile davor.\n'.repeat(50)}Die gesuchte Stelle.\n${'Zeile danach.\n'.repeat(50)}`
      // jsdom lays nothing out; the passage sits 900px below the top of the view
      Object.defineProperty(Range.prototype, 'getBoundingClientRect', {
        configurable: true,
        value: () => ({ top: 900 }) as DOMRect,
      })
      onTestFinished(() => {
        delete (Range.prototype as { getBoundingClientRect?: unknown }).getBoundingClientRect
      })
      const doc = { ...plainTextDoc(text), anchor: { quoteStart: 'Die gesuchte Stelle.' } }
      renderWithProviders(<DocumentTextPreviewDialog previewDocument={doc} onClose={vi.fn()} />)

      await screen.findByText(/Fundstelle markiert/)
      const content = document.querySelector('pre')!.parentElement!
      expect(content.scrollTop).toBe(900)
      expect(document.activeElement).toBe(document.querySelector('pre'))
    })

    it('marks the passage of a document opened while the dialog is open', async () => {
      const first = {
        ...plainTextDoc('Alpha Satz. Beta Satz.'),
        anchor: { quoteStart: 'Alpha Satz.' },
      }
      const second = {
        ...plainTextDoc('Gamma Satz. Delta Satz.'),
        anchor: { quoteStart: 'Delta Satz.' },
      }
      const { rerender } = renderWithProviders(
        <DocumentTextPreviewDialog previewDocument={first} onClose={vi.fn()} />,
      )
      await screen.findByText('Fundstelle markiert.')

      rerender(<DocumentTextPreviewDialog previewDocument={second} onClose={vi.fn()} />)

      await screen.findByText('Gamma Satz. Delta Satz.')
      const highlight = highlights.get('opaa-passage') as { ranges: Range[] }
      expect(highlight.ranges[0].toString()).toBe('Delta Satz.')
    })

    it('says it only scrolled where the browser cannot highlight', async () => {
      vi.stubGlobal('CSS', {})
      const doc = { ...plainTextDoc('Ein Satz.'), anchor: { quoteStart: 'Ein Satz.' } }
      renderWithProviders(<DocumentTextPreviewDialog previewDocument={doc} onClose={vi.fn()} />)

      expect(await screen.findByRole('status')).toHaveTextContent('Zur Fundstelle gescrollt.')
    })

    it('removes the highlight when the dialog closes', async () => {
      const doc = { ...plainTextDoc('Ein Satz.'), anchor: { quoteStart: 'Ein Satz.' } }
      const { rerender } = renderWithProviders(
        <DocumentTextPreviewDialog previewDocument={doc} onClose={vi.fn()} />,
      )
      expect(await screen.findByRole('status')).toHaveTextContent('Fundstelle markiert.')

      rerender(<DocumentTextPreviewDialog previewDocument={null} onClose={vi.fn()} />)

      expect(highlights.size).toBe(0)
    })
  })
})
