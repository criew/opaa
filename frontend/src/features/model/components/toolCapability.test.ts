import { describe, expect, it } from 'vitest'
import { toolCapabilityBasisLine } from './toolCapability'

describe('toolCapabilityBasisLine', () => {
  it('says nothing while the capability is unverified', () => {
    expect(
      toolCapabilityBasisLine({
        value: 'UNVERIFIED',
        basis: null,
        assessedAt: null,
        measurementRun: null,
      }),
    ).toBeNull()
  })

  it('names how and when a statement was checked, a measurement with its run', () => {
    expect(
      toolCapabilityBasisLine({
        value: 'CAPABLE',
        basis: 'MEASURED',
        assessedAt: '2026-11-09T10:00:00Z',
        measurementRun: 'Lauf 7',
      }),
    ).toBe('gemessen · 09.11.2026 · Lauf 7')
    expect(
      toolCapabilityBasisLine({
        value: 'NOT_CAPABLE',
        basis: 'MANUAL',
        assessedAt: '2026-11-09T10:00:00Z',
        measurementRun: null,
      }),
    ).toBe('manuell gesetzt · 09.11.2026')
  })
})
