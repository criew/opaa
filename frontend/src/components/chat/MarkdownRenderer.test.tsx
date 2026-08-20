import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import MarkdownRenderer from './MarkdownRenderer'
import { buildCitationIndex } from './citations'

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

  it('renders citation markers as superscript footnote anchors (#590)', () => {
    const content = 'The answer is 42【source: doc-1#0 | readme.md】.'
    renderWithProviders(
      <MarkdownRenderer
        content={content}
        citations={buildCitationIndex(content, undefined)}
        messageId="m1"
      />,
    )
    expect(screen.getByText(/The answer is 42/)).toBeInTheDocument()
    const anchor = screen.getByRole('link', { name: 'Fundstelle 1: readme.md' })
    expect(anchor).toHaveTextContent('1')
    expect(anchor).toHaveAttribute('href', '#fundstelle-m1-0')
  })

  it('numbers multiple citations in order of appearance (#590)', () => {
    const content = 'Info【source: id-1#0 | arch.md】 und【source: id-2#3 | deploy.pdf】.'
    renderWithProviders(
      <MarkdownRenderer
        content={content}
        citations={buildCitationIndex(content, undefined)}
        messageId="m2"
      />,
    )
    expect(screen.getByRole('link', { name: 'Fundstelle 1: arch.md' })).toHaveTextContent('1')
    expect(screen.getByRole('link', { name: 'Fundstelle 2: deploy.pdf' })).toHaveTextContent('2')
  })

  it('strips markers when no citation index is provided', () => {
    renderWithProviders(<MarkdownRenderer content="Satz【source: doc-1#0 | readme.md】 Ende." />)
    expect(screen.getByText(/Satz\s*Ende\./)).toBeInTheDocument()
    expect(screen.queryByText(/source:/)).not.toBeInTheDocument()
  })

  it('does not render citation chips when no citations present', () => {
    renderWithProviders(<MarkdownRenderer content="Just a normal (parenthetical) remark" />)
    expect(screen.getByText('Just a normal (parenthetical) remark')).toBeInTheDocument()
  })
})
