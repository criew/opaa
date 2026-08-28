import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import BrandingPreview from './BrandingPreview'

describe('BrandingPreview', () => {
  // regression guard for #956: the preview is aria-hidden by design (its values duplicate the
  // labelled form fields), so nothing inside it may remain keyboard-reachable - a focus stop
  // that screen readers cannot see. inert removes the subtree from the tab order.
  it('keeps the aria-hidden preview out of the tab order via inert', () => {
    const { container } = renderWithProviders(
      <BrandingPreview mode="light" productName="OPAA" claim="Testclaim" primaryColor="#1976d2" />,
    )

    const preview = container.querySelector('[aria-hidden="true"]')
    expect(preview).not.toBeNull()
    expect(preview).toHaveAttribute('inert')

    const previewButton = screen.getByText('Fortfahren')
    expect(previewButton.closest('[inert]')).not.toBeNull()
  })
})
