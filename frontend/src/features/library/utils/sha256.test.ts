import { describe, expect, it } from 'vitest'
import { sha256Hex } from './sha256'

describe('sha256Hex', () => {
  it('hashes the bytes to lower-case hex, as the backend stores the checksum', async () => {
    expect(await sha256Hex(new TextEncoder().encode('abc').buffer)).toBe(
      'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
    )
  })
})
