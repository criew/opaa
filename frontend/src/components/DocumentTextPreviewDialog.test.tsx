import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import DocumentTextPreviewDialog from './DocumentTextPreviewDialog'
import type { TextPreviewResult } from '../utils/documentContent'

function markdownDoc(content: string, fileName = '001_personalausweis.md'): TextPreviewResult {
  return { kind: 'text-preview', fileName, contentType: 'text/markdown', content }
}

function plainTextDoc(content: string, fileName = 'notiz.txt'): TextPreviewResult {
  return { kind: 'text-preview', fileName, contentType: 'text/plain', content }
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
    // #1016: heading elements are normalised per rendered content (rank compression from h2);
    // the h5 LOOK of "#" survives as the Typography variant.
    const heading1016 = screen.getByText('Titel').closest('h2')
    expect(heading1016).toBeInTheDocument()
    expect(heading1016).toHaveClass('MuiTypography-h5')
    expect(screen.getByText('Ein Absatz.')).toBeInTheDocument()
  })

  // #781 review, Nit 4: MUI does not wire aria-labelledby to DialogTitle automatically.
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
    await user.click(screen.getByRole('button', { name: 'Vorschau schließen' }))
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  // #781 review, Nit 6: a document preview's content is not a chat answer - MarkdownRenderer's
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

  // #780 acceptance criteria: no gerendertes Markdown may execute script in the app's origin - a
  // literal <script>/onerror-carrying <img> is inert text (react-markdown never enables rehype-raw,
  // so raw HTML is parsed as text, not markup), and a javascript: link/image URL is stripped by
  // react-markdown's default urlTransform (mirrors the #743 SVG Sperre for a different surface).
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

    it('strips a javascript: URL from a Markdown link instead of rendering it as href', () => {
      renderWithProviders(
        <DocumentTextPreviewDialog
          previewDocument={markdownDoc("[Klick mich](javascript:alert('x'))")}
          onClose={vi.fn()}
        />,
      )
      const link = screen.getByText('Klick mich').closest('a')
      expect(link).not.toBeNull()
      expect(link).not.toHaveAttribute('href', expect.stringContaining('javascript:'))
    })

    it('strips a javascript: URL from a Markdown image src', () => {
      renderWithProviders(
        <DocumentTextPreviewDialog
          previewDocument={markdownDoc("![Bild](javascript:alert('x'))")}
          onClose={vi.fn()}
        />,
      )
      const img = document.querySelector('img')
      expect(img).toBeInTheDocument()
      expect(img).not.toHaveAttribute('src', expect.stringContaining('javascript:'))
    })
  })
})
