import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import { OPAA_BRANDING, useBrandingStore } from '../stores/brandingStore'
import AboutDialog from './AboutDialog'

beforeEach(() => {
  useBrandingStore.setState({ branding: OPAA_BRANDING })
})

describe('AboutDialog', () => {
  // regression guard for #2463: the version is the one the build was given (OPAA_VERSION), not a
  // literal in the source; a build without it - tests included - is the development state.
  it('shows the build version, the development placeholder without OPAA_VERSION', () => {
    renderWithProviders(<AboutDialog open onClose={() => {}} />)

    const dialog = screen.getByRole('dialog', { name: 'Info zu OPAA' })
    expect(dialog).toHaveTextContent('OPAA v0.0.0-dev')
    expect(dialog).not.toHaveTextContent('v0.1.0')
  })
})
