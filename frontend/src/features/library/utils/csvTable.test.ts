import { describe, expect, it } from 'vitest'
import { parseCsv } from './csvTable'

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

  it('takes the separator the head line uses most, outside quotes', () => {
    expect(parseCsv('"a;b",c,d\n1,2,3').head).toEqual(['a;b', 'c', 'd'])
    expect(parseCsv('a\tb\tc\n1\t2\t3').rows[0].cells).toEqual(['1', '2', '3'])
  })

  it('keeps separators, doubled quotes and line breaks inside quoted cells', () => {
    const table = parseCsv('A;B\n"x;y";"er sagte ""ja""\nund ging"\n')
    expect(table.rows).toEqual([{ number: 2, cells: ['x;y', 'er sagte "ja"\nund ging'] }])
  })

  it('skips empty lines, so the numbers match the rows of the index', () => {
    const table = parseCsv('A;B\n\n1;2\n\n3;4')
    expect(table.rows.map((row) => row.number)).toEqual([2, 3])
    expect(table.rows[1].cells).toEqual(['3', '4'])
  })

  it('drops a byte order mark and reads an empty file as an empty table', () => {
    expect(parseCsv('﻿A;B\n1;2').head).toEqual(['A', 'B'])
    expect(parseCsv('')).toEqual({ head: [], rows: [] })
  })
})
