import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import MarkdownRenderer from './MarkdownRenderer'

describe('MarkdownRenderer', () => {
  it('renders plain text', () => {
    renderWithProviders(<MarkdownRenderer content="Hello world" />)
    expect(screen.getByText('Hello world')).toBeInTheDocument()
  })

  it('renders bold text', () => {
    renderWithProviders(<MarkdownRenderer content="This is **bold** text" />)
    expect(screen.getByText('bold')).toHaveStyle({ fontWeight: '700' })
  })

  it('renders headings', () => {
    renderWithProviders(<MarkdownRenderer content="# Heading 1" />)
    expect(screen.getByText('Heading 1').closest('h5')).toBeInTheDocument()
  })

  it('renders inline code', () => {
    renderWithProviders(<MarkdownRenderer content="Use `useState` hook" />)
    const code = screen.getByText('useState')
    expect(code.tagName).toBe('CODE')
  })

  it('renders code blocks', () => {
    const content = '```javascript\nconst x = 1;\n```'
    renderWithProviders(<MarkdownRenderer content={content} />)
    const pre = document.querySelector('pre')
    expect(pre).toBeInTheDocument()
    expect(pre?.textContent).toContain('const x = 1')
  })

  it('renders links with target _blank', () => {
    renderWithProviders(<MarkdownRenderer content="[Example](https://example.com)" />)
    const link = screen.getByText('Example')
    expect(link.closest('a')).toHaveAttribute('target', '_blank')
    expect(link.closest('a')).toHaveAttribute('href', 'https://example.com')
  })

  it('renders unordered lists', () => {
    renderWithProviders(<MarkdownRenderer content={'- Item 1\n- Item 2'} />)
    const items = document.querySelectorAll('li')
    expect(items).toHaveLength(2)
    expect(items[0].textContent).toBe('Item 1')
    expect(items[1].textContent).toBe('Item 2')
  })

  it('renders tables', () => {
    const content = '| Name | Age |\n| --- | --- |\n| Alice | 30 |'
    renderWithProviders(<MarkdownRenderer content={content} />)
    const cells = document.querySelectorAll('th, td')
    expect(cells.length).toBeGreaterThanOrEqual(4)
    expect(cells[0].textContent).toBe('Name')
  })

  it('extracts source citation from end of content', () => {
    renderWithProviders(<MarkdownRenderer content="The answer is 42 (architecture-overview.md)" />)
    expect(screen.getByText('The answer is 42')).toBeInTheDocument()
    expect(screen.getByText(/Quelle:.*architecture-overview\.md/)).toBeInTheDocument()
  })

  it('extracts source citation followed by a period', () => {
    renderWithProviders(
      <MarkdownRenderer content="Die Mehrwertsteuer beträgt 0,79€ (miles-rechnung-45163F60.pdf)." />,
    )
    expect(screen.getByText(/Mehrwertsteuer/)).toBeInTheDocument()
    expect(screen.getByText(/Quelle:.*miles-rechnung-45163F60\.pdf/)).toBeInTheDocument()
  })

  it('extracts source citation with "Quelle:" prefix', () => {
    renderWithProviders(
      <MarkdownRenderer content="Hier ist die Antwort (Quelle: Modulhandbuch-FHDO.pdf)" />,
    )
    expect(screen.getByText('Hier ist die Antwort')).toBeInTheDocument()
    expect(screen.getByText(/Quelle:.*Modulhandbuch-FHDO\.pdf/)).toBeInTheDocument()
  })

  it('does not extract citation when no file reference at end', () => {
    renderWithProviders(<MarkdownRenderer content="Just a normal (parenthetical) remark" />)
    expect(screen.getByText('Just a normal (parenthetical) remark')).toBeInTheDocument()
    expect(screen.queryByText(/Quelle:/)).not.toBeInTheDocument()
  })
})
