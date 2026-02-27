import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import SourceCard from './SourceCard'

describe('SourceCard', () => {
  it('renders file name and relevance', () => {
    render(
      <SourceCard
        source={{ fileName: 'readme.md', relevanceScore: 0.92, excerpt: 'Some text', cited: true }}
      />,
    )
    expect(screen.getByText('readme.md')).toBeInTheDocument()
    expect(screen.getByText('92% relevant')).toBeInTheDocument()
  })

  it('shows Public badge for .md files', () => {
    render(
      <SourceCard
        source={{ fileName: 'readme.md', relevanceScore: 0.5, excerpt: '', cited: true }}
      />,
    )
    expect(screen.getByText('Public')).toBeInTheDocument()
  })

  it('shows Confidential badge for .pdf files', () => {
    render(
      <SourceCard
        source={{ fileName: 'report.pdf', relevanceScore: 0.8, excerpt: '', cited: true }}
      />,
    )
    expect(screen.getByText('Confidential')).toBeInTheDocument()
  })

  it('shows Internal badge for other files', () => {
    render(
      <SourceCard
        source={{ fileName: 'config.yaml', relevanceScore: 0.6, excerpt: '', cited: true }}
      />,
    )
    expect(screen.getByText('Internal')).toBeInTheDocument()
  })

  it('shows tooltip on access level badge hover', async () => {
    const user = userEvent.setup()
    render(
      <SourceCard
        source={{ fileName: 'config.yaml', relevanceScore: 0.6, excerpt: '', cited: true }}
      />,
    )
    await user.hover(screen.getByText('Internal'))
    expect(await screen.findByText('Access levels coming in a future release')).toBeInTheDocument()
  })

  it('renders cited source with full opacity', () => {
    const { container } = render(
      <SourceCard
        source={{ fileName: 'readme.md', relevanceScore: 0.9, excerpt: 'Text', cited: true }}
      />,
    )
    const paper = container.firstChild as HTMLElement
    expect(paper).not.toHaveStyle({ opacity: '0.5' })
  })

  it('renders uncited source with reduced opacity', () => {
    const { container } = render(
      <SourceCard
        source={{ fileName: 'readme.md', relevanceScore: 0.9, excerpt: 'Text', cited: false }}
      />,
    )
    const paper = container.firstChild as HTMLElement
    expect(paper).toHaveStyle({ opacity: '0.5' })
  })
})
