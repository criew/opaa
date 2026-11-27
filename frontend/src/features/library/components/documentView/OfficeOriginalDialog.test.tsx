import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '@/test/test-utils'
import type { ChunkAnchor } from '@/shared/api/types'
import { parseCsv } from '../../utils/csvTable'
import { parseMail } from '../../utils/mailMessage'
import type { OfficeOriginal } from '../../utils/officeOriginal'
import OfficeOriginalDialog from './OfficeOriginalDialog'

const docx = vi.hoisted(() => ({
  renderAsync: vi.fn(),
  options: null as unknown,
}))

vi.mock('docx-preview', () => ({ renderAsync: docx.renderAsync }))

const CSV = [
  'Aktenzeichen;Antragsteller;Fällig',
  'WF-2026-014;Café Lindner GbR;30.09.2026',
  'WF-2026-037;Optik Brandt;31.01.2027',
  'WF-2026-040;Kreativraum UG;28.02.2027',
].join('\r\n')

const MAIL = [
  'From: Jana Brandt <jana@optik.example>',
  'To: Förderreferat <foerderung@stadt.example>',
  'Subject: Rückfrage Verwendungsnachweis',
  'Date: Mon, 16 Nov 2026 09:42:00 +0100',
  '',
  'Sehr geehrte Damen und Herren,',
  '',
  'können wir die Frist um drei Monate verlängern?',
  '',
  'Mit freundlichen Grüßen',
].join('\r\n')

function csv(anchor?: ChunkAnchor | null): OfficeOriginal {
  return {
    format: 'csv',
    documentId: 'doc-csv',
    fileName: 'fristenliste.csv',
    blob: new Blob([CSV], { type: 'text/csv' }),
    table: parseCsv(CSV),
    anchor,
  }
}

function mail(anchor?: ChunkAnchor | null): OfficeOriginal {
  return {
    format: 'mail',
    documentId: 'doc-mail',
    fileName: 'rueckfrage.eml',
    blob: new Blob([MAIL], { type: 'message/rfc822' }),
    mail: parseMail(MAIL)!,
    anchor,
  }
}

function word(anchor?: ChunkAnchor | null): OfficeOriginal {
  return {
    format: 'docx',
    documentId: 'doc-docx',
    fileName: 'rundschreiben.docx',
    blob: new Blob(['PK'], { type: 'application/octet-stream' }),
    anchor,
  }
}

function renderDialog(original: OfficeOriginal, onUnreadable = vi.fn()) {
  return renderWithProviders(
    <OfficeOriginalDialog original={original} onClose={vi.fn()} onUnreadable={onUnreadable} />,
  )
}

const highlights = new Map<string, { ranges: Range[] }>()

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
  docx.renderAsync.mockReset()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('OfficeOriginalDialog', () => {
  it('stays closed without an original', () => {
    renderWithProviders(
      <OfficeOriginalDialog original={null} onClose={vi.fn()} onUnreadable={vi.fn()} />,
    )
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('opens loading while the original is fetched, with one status', () => {
    renderWithProviders(
      <OfficeOriginalDialog
        original={null}
        pending={{ fileName: 'fristenliste.csv', anchor: { rowFrom: 3 } }}
        onClose={vi.fn()}
        onUnreadable={vi.fn()}
      />,
    )
    expect(screen.getByRole('dialog', { name: 'fristenliste.csv' })).toBeVisible()
    expect(screen.getByRole('button', { name: 'Zurück zur Antwort' })).toBeVisible()
    expect(screen.getAllByRole('status')).toEqual([screen.getByText('Wird geladen').parentElement])
  })

  describe('CSV', () => {
    it('shows the table with its row numbers and marks the rows the anchor names', async () => {
      renderDialog(csv({ rowFrom: 3, rowTo: 3, quoteStart: 'WF-2026-037 | Optik Brandt' }))

      expect(screen.getByRole('dialog', { name: 'fristenliste.csv' })).toBeVisible()
      expect(screen.getByRole('columnheader', { name: 'Zeile' })).toBeInTheDocument()
      const marked = screen.getByRole('row', { name: /WF-2026-037/ })
      expect(marked).toHaveAttribute('data-passage-row')
      expect(screen.getByRole('row', { name: /WF-2026-014/ })).not.toHaveAttribute(
        'data-passage-row',
      )
      expect(marked).toHaveTextContent('3')
      await waitFor(() =>
        expect(screen.getByRole('status')).toHaveTextContent('Fundstelle markiert.'),
      )
      expect(screen.getByText('Zeile 3')).toBeVisible()
      // the card quotes the cells, not the raw separators
      expect(screen.getByText('„WF-2026-037 · Optik Brandt“')).toBeVisible()
      expect(screen.getAllByRole('status')).toHaveLength(1)
    })

    it('finds the row through the quote when the anchor names none', async () => {
      renderDialog(csv({ quoteStart: 'WF-2026-040;Kreativraum UG' }))

      await waitFor(() =>
        expect(screen.getByRole('row', { name: /WF-2026-040/ })).toHaveAttribute(
          'data-passage-row',
        ),
      )
      expect(screen.getByRole('status')).toHaveTextContent('Fundstelle markiert.')
      expect(screen.getByText('Zeile 4')).toBeVisible()
    })

    it('marks the row the index numbers, blank lines counted', async () => {
      const text = 'Aktenzeichen;Antragsteller\r\n\r\nWF-1;Lindner\r\n;\r\nWF-2;Brandt\r\n'
      renderDialog({ ...csv({ rowFrom: 5, rowTo: 5 }), format: 'csv', table: parseCsv(text) })

      expect(screen.getByRole('row', { name: /WF-2/ })).toHaveAttribute('data-passage-row')
      expect(screen.getByRole('row', { name: /WF-1/ })).not.toHaveAttribute('data-passage-row')
      expect(screen.getAllByRole('row')).toHaveLength(3)
    })

    it('says so when the rows are not in the table', async () => {
      renderDialog(csv({ rowFrom: 40, rowTo: 40 }))
      expect(await screen.findByRole('status')).toHaveTextContent(
        'Die Fundstelle wurde im Dokument nicht gefunden.',
      )
      expect(screen.queryByRole('button', { name: 'Zur Fundstelle' })).toBeNull()
    })
  })

  describe('mail', () => {
    it('shows head and text and marks the quoted passage', async () => {
      renderDialog(mail({ quoteStart: 'können wir die Frist um drei Monate verlängern?' }))

      expect(screen.getByRole('heading', { name: 'Rückfrage Verwendungsnachweis' })).toBeVisible()
      expect(screen.getByText('Jana Brandt <jana@optik.example>')).toBeVisible()
      expect(screen.getByText('Datum')).toBeVisible()
      await waitFor(() =>
        expect(screen.getByRole('status')).toHaveTextContent('Fundstelle markiert.'),
      )
      const range = highlights.get('opaa-original-passage')!.ranges[0]
      expect(range.toString()).toMatch(/^können wir die Frist um drei Monate verlängern/)
      expect(screen.getByText('können wir die Frist um drei Monate verlängern?')).toHaveAttribute(
        'data-passage',
      )
      expect(await screen.findByTestId('passage-label')).toHaveTextContent('Fundstelle')
      expect(screen.getByRole('button', { name: 'Zur Fundstelle' })).toBeVisible()
    })

    it('says so for a citation without a stored anchor', () => {
      renderDialog(mail(null))
      expect(screen.getByRole('status')).toHaveTextContent(
        'Für diesen Beleg ist keine Fundstelle gespeichert',
      )
    })

    it('shows no card and returns plainly when opened without a citation', () => {
      renderDialog(mail())
      expect(screen.queryByRole('heading', { name: 'Fundstelle' })).toBeNull()
      expect(screen.getByRole('button', { name: 'Zurück' })).toBeVisible()
      expect(screen.getByRole('status')).toBeEmptyDOMElement()
    })
  })

  describe('Word', () => {
    it('renders through docx-preview without embedded HTML, comments, changes and fonts', async () => {
      docx.renderAsync.mockImplementation(async (_blob, body: HTMLElement) => {
        body.innerHTML =
          '<section class="docx"><p>2. Fristen</p><p>Der Verwendungsnachweis ist vorzulegen.</p>' +
          '<p><a href="javascript:alert(1)">Böse</a> <a href="https://stadt.example/">Gut</a></p></section>'
      })
      renderDialog(word({ quoteStart: 'Der Verwendungsnachweis ist vorzulegen.' }))

      await waitFor(() =>
        expect(screen.getByRole('status')).toHaveTextContent('Fundstelle markiert.'),
      )
      const [, , , options] = docx.renderAsync.mock.calls[0]
      expect(options).toMatchObject({
        renderAltChunks: false,
        renderComments: false,
        renderChanges: false,
        ignoreFonts: true,
        experimental: false,
        useBase64URL: true,
      })
      const root = screen.getByTestId('docx-original').shadowRoot!
      const links = root.querySelectorAll('a')
      expect(links[0].hasAttribute('href')).toBe(false)
      expect(links[1].getAttribute('href')).toBe('https://stadt.example/')
      expect(links[1].getAttribute('rel')).toBe('noopener noreferrer')
      expect(root.querySelector('[data-passage]')).toHaveTextContent(
        'Der Verwendungsnachweis ist vorzulegen.',
      )
      // the document's own styles stay inside its shadow root
      expect(document.querySelector('section.docx')).toBeNull()
    })

    it('keeps a VML shape that asks for the whole window inside the document box', async () => {
      docx.renderAsync.mockImplementation(async (_blob, body: HTMLElement) => {
        body.innerHTML =
          '<section class="docx"><p>Frist</p><div><svg style="position:fixed;inset:0;' +
          'z-index:2147483647"></svg></div></section>'
      })
      renderDialog(word({ quoteStart: 'Frist' }))

      await waitFor(() =>
        expect(screen.getByRole('status')).toHaveTextContent('Fundstelle markiert.'),
      )
      const host = screen.getByTestId('docx-original')
      expect(host.shadowRoot!.querySelector<SVGElement>('svg')!.style.position).toBe('')
      expect(getComputedStyle(host.parentElement!).overflow).toBe('clip')
    })

    it('hands a file docx-preview cannot read over to the Textfassung', async () => {
      docx.renderAsync.mockRejectedValue(new Error('not a zip'))
      const onUnreadable = vi.fn()
      const original = word({ quoteStart: 'x' })
      renderDialog(original, onUnreadable)
      await waitFor(() => expect(onUnreadable).toHaveBeenCalledWith(original))
    })
  })

  it('downloads the very bytes it shows', async () => {
    const createObjectURL = vi.fn(() => 'blob:x')
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() }))
    const original = csv({ rowFrom: 2, rowTo: 2 })
    renderDialog(original)
    await userEvent.click(screen.getByRole('button', { name: 'Original herunterladen' }))
    expect(createObjectURL).toHaveBeenCalledWith(original.blob)
  })
})
