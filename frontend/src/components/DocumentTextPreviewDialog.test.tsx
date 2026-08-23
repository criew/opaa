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
    renderWithProviders(<DocumentTextPreviewDialog document={null} onClose={vi.fn()} />)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('shows the file name and renders Markdown content readable in the browser', () => {
    renderWithProviders(
      <DocumentTextPreviewDialog
        document={markdownDoc('# Titel\n\nEin Absatz.')}
        onClose={vi.fn()}
      />,
    )
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByText('001_personalausweis.md')).toBeInTheDocument()
    expect(screen.getByText('Titel').closest('h5')).toBeInTheDocument()
    expect(screen.getByText('Ein Absatz.')).toBeInTheDocument()
  })

  it('renders plain text as-is, preserving line breaks', () => {
    renderWithProviders(
      <DocumentTextPreviewDialog document={plainTextDoc('Zeile 1\nZeile 2')} onClose={vi.fn()} />,
    )
    const pre = document.querySelector('pre')
    expect(pre).toBeInTheDocument()
    expect(pre?.textContent).toBe('Zeile 1\nZeile 2')
  })

  it('fires onClose when the close button is clicked', async () => {
    const onClose = vi.fn()
    const user = userEvent.setup()
    renderWithProviders(
      <DocumentTextPreviewDialog document={markdownDoc('Inhalt')} onClose={onClose} />,
    )
    await user.click(screen.getByRole('button', { name: 'Vorschau schließen' }))
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  // #780 acceptance criteria: no gerendertes Markdown may execute script in the app's origin - a
  // literal <script>/onerror-carrying <img> is inert text (react-markdown never enables rehype-raw,
  // so raw HTML is parsed as text, not markup), and a javascript: link/image URL is stripped by
  // react-markdown's default urlTransform (mirrors the #743 SVG Sperre for a different surface).
  describe('security: no script execution from rendered Markdown (#780)', () => {
    it('renders a literal <script> tag as inert text, not a script element', () => {
      renderWithProviders(
        <DocumentTextPreviewDialog
          document={markdownDoc(
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
          document={markdownDoc('<img src=x onerror="window.__pwned = true">')}
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
          document={markdownDoc("[Klick mich](javascript:alert('x'))")}
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
          document={markdownDoc("![Bild](javascript:alert('x'))")}
          onClose={vi.fn()}
        />,
      )
      const img = document.querySelector('img')
      expect(img).toBeInTheDocument()
      expect(img).not.toHaveAttribute('src', expect.stringContaining('javascript:'))
    })
  })
})
