import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import SourceCard from './SourceCard'

const baseSource = {
  fileName: 'readme.md',
  relevanceScore: 0.92,
  matchCount: 2,
  indexedAt: '2025-01-15T10:30:00Z',
  cited: true,
}

describe('SourceCard', () => {
  it('renders file name and relevance', () => {
    render(<SourceCard source={baseSource} />)
    expect(screen.getByText('readme.md')).toBeInTheDocument()
    expect(screen.getByText('92% relevant')).toBeInTheDocument()
  })

  it('renders match count', () => {
    render(<SourceCard source={baseSource} />)
    expect(screen.getByText('2 Treffer')).toBeInTheDocument()
  })

  it('renders indexed date', () => {
    render(<SourceCard source={baseSource} />)
    expect(screen.getByText(/Indexiert:/)).toBeInTheDocument()
  })

  it('renders dash when indexedAt is null', () => {
    render(<SourceCard source={{ ...baseSource, indexedAt: null }} />)
    expect(screen.getByText('Indexiert: -')).toBeInTheDocument()
  })

  it('shows Public badge for .md files', () => {
    render(<SourceCard source={baseSource} />)
    expect(screen.getByText('Public')).toBeInTheDocument()
  })

  it('shows Confidential badge for .pdf files', () => {
    render(<SourceCard source={{ ...baseSource, fileName: 'report.pdf' }} />)
    expect(screen.getByText('Confidential')).toBeInTheDocument()
  })

  it('shows Internal badge for other files', () => {
    render(<SourceCard source={{ ...baseSource, fileName: 'config.yaml' }} />)
    expect(screen.getByText('Internal')).toBeInTheDocument()
  })

  it('shows tooltip on access level badge hover', async () => {
    const user = userEvent.setup()
    render(<SourceCard source={{ ...baseSource, fileName: 'config.yaml' }} />)
    await user.hover(screen.getByText('Internal'))
    expect(await screen.findByText('Access levels coming in a future release')).toBeInTheDocument()
  })

  it('renders cited source with full opacity', () => {
    const { container } = render(<SourceCard source={baseSource} />)
    const paper = container.firstChild as HTMLElement
    expect(paper).not.toHaveStyle({ opacity: '0.6' })
  })

  it('renders uncited source with reduced opacity', () => {
    const { container } = render(<SourceCard source={{ ...baseSource, cited: false }} />)
    const paper = container.firstChild as HTMLElement
    expect(paper).toHaveStyle({ opacity: '0.6' })
  })
})
