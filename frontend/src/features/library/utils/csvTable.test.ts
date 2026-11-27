import { describe, expect, it } from 'vitest'
import { decodeCsv, parseCsv } from './csvTable'

describe('parseCsv', () => {
  it('reads a semicolon table with its head and numbers the records from the head', () => {
    expect(
      parseCsv('Name;Frist\r\nCafé Lindner;30.09.2026\r\nOptik Brandt;31.01.2027\r\n'),
    ).toEqual({
      head: ['Name', 'Frist'],
      rows: [
        { number: 2, cells: ['Café Lindner', '30.09.2026'] },
        { number: 3, cells: ['Optik Brandt', '31.01.2027'] },
      ],
    })
  })

  it('picks the separator the first lines agree on, comma on a tie', () => {
    expect(parseCsv('"a;b",c,d\n1,2,3').head).toEqual(['a;b', 'c', 'd'])
    expect(parseCsv('a\tb\tc\n1\t2\t3').rows[0].cells).toEqual(['1', '2', '3'])
    expect(parseCsv('a,b;c\n1,2;3').head).toEqual(['a', 'b;c'])
  })

  it('does not take a separator that breaks the quoting, as the index does', () => {
    const table = parseCsv('"Leistung, allgemein";Betrag\n"Ausstellung";37 EUR\n')
    expect(table.head).toEqual(['Leistung, allgemein', 'Betrag'])
    expect(table.rows).toEqual([{ number: 2, cells: ['Ausstellung', '37 EUR'] }])
  })

  it('keeps separators, doubled quotes and line breaks inside quoted cells', () => {
    const table = parseCsv('A;B\n"x;y";"er sagte ""ja""\nund ging"\n3;4')
    expect(table.rows).toEqual([
      { number: 2, cells: ['x;y', 'er sagte "ja"\nund ging'] },
      { number: 3, cells: ['3', '4'] },
    ])
  })

  it('counts blank lines as records without showing them, like the index', () => {
    const table = parseCsv('\nA;B\n\n1;2\n ; \n3;4')
    expect(table.head).toEqual(['A', 'B'])
    expect(table.rows).toEqual([
      { number: 4, cells: ['1', '2'] },
      { number: 6, cells: ['3', '4'] },
    ])
  })

  it('trims the cells and reads a quote inside a cell as text', () => {
    expect(parseCsv(' A ; B \n 1 ;2"3 ').rows[0].cells).toEqual(['1', '2"3'])
  })

  it('shows a sole record as a row, as the index cites it', () => {
    expect(parseCsv('Name,Amt\n')).toEqual({
      head: [],
      rows: [{ number: 1, cells: ['Name', 'Amt'] }],
    })
  })

  it('drops a byte order mark and reads an empty file as an empty table', () => {
    expect(parseCsv('﻿A;B\n1;2').head).toEqual(['A', 'B'])
    expect(parseCsv('')).toEqual({ head: [], rows: [] })
    expect(parseCsv(' \n\n')).toEqual({ head: [], rows: [] })
  })
})

describe('decodeCsv', () => {
  it('reads UTF-8 without its byte order mark', () => {
    const bytes = new Uint8Array([0xef, 0xbb, 0xbf, ...new TextEncoder().encode('Name;Straße')])
    expect(decodeCsv(bytes)).toBe('Name;Straße')
  })

  it('falls back to Windows-1252 where the bytes are not UTF-8', () => {
    // "Müller;Königsallee€" as German Excel saves it
    const bytes = new Uint8Array([
      0x4d, 0xfc, 0x6c, 0x6c, 0x65, 0x72, 0x3b, 0x4b, 0xf6, 0x6e, 0x69, 0x67, 0x73, 0x61, 0x6c,
      0x6c, 0x65, 0x65, 0x80,
    ])
    expect(decodeCsv(bytes)).toBe('Müller;Königsallee€')
  })
})
