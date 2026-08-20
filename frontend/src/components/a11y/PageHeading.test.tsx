import { screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import PageHeading, { MAIN_CONTENT_ID } from './PageHeading'

function mountMain(): HTMLElement {
  const main = document.createElement('main')
  main.id = MAIN_CONTENT_ID
  main.tabIndex = -1
  document.body.appendChild(main)
  return main
}

describe('PageHeading', () => {
  afterEach(() => {
    document.getElementById(MAIN_CONTENT_ID)?.remove()
  })

  it('renders the single h1 and sets the document title', () => {
    renderWithProviders(<PageHeading title="Einstellungen" />)
    expect(screen.getByRole('heading', { level: 1, name: 'Einstellungen' })).toBeInTheDocument()
    expect(document.title).toBe('Einstellungen · OPAA')
  })

  it('keeps the heading for assistive technology when visually hidden', () => {
    renderWithProviders(<PageHeading title="Chat" visuallyHidden />)
    expect(screen.getByRole('heading', { level: 1, name: 'Chat' })).toBeInTheDocument()
  })

  it('takes focus when it mounts while <main> holds the route-change focus', () => {
    const main = mountMain()
    main.focus()
    renderWithProviders(<PageHeading title="Gruppen" />)
    expect(screen.getByRole('heading', { level: 1 })).toHaveFocus()
  })

  it('leaves focus alone when <main> is not focused', () => {
    mountMain()
    renderWithProviders(<PageHeading title="Gruppen" />)
    expect(screen.getByRole('heading', { level: 1 })).not.toHaveFocus()
  })

  it('lets the document title be more specific than the heading', () => {
    renderWithProviders(<PageHeading title="Chat" documentTitle="Architektur des Projekts" />)
    expect(screen.getByRole('heading', { level: 1, name: 'Chat' })).toBeInTheDocument()
    expect(document.title).toBe('Architektur des Projekts · OPAA')
  })
})
