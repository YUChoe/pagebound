import { describe, expect, it } from 'vitest'
import { ageLevels, blameRows } from '../src/blame.js'
import { collapseRows } from '../src/history.js'

describe('blame 표시', () => {
  it('hunk를 줄 목록으로 펼치고 묶음 첫 줄 표시', () => {
    const rows = blameRows('x\na\nB', [{ start: 1, count: 1, version: 3 }, { start: 2, count: 2, version: 1 }])
    expect(rows).toEqual([
      { no: 1, text: 'x', version: 3, first: true },
      { no: 2, text: 'a', version: 1, first: true },
      { no: 3, text: 'B', version: 1, first: false },
    ])
  })

  it('작성 시각 순으로 나이 단계', () => {
    const levels = ageLevels({ 1: { createdAt: '2023-01-01' }, 3: { createdAt: '2026-01-01' }, 2: { createdAt: '2024-01-01' } })
    expect(levels).toEqual({ 1: 0, 2: 0.5, 3: 1 })
  })
})


describe('역사 diff 접기', () => {
  it('변경 주변 3줄만 남기고 나머지 같은 줄은 접는다', () => {
    const rows = Array.from({ length: 20 }, (_, i) => ({ type: i === 10 ? 'change' : 'equal' }))
    const blocks = collapseRows(rows, 3)
    expect(blocks.map((b) => b.hidden ?? b.rows.length)).toEqual([7, 7, 6])
    expect(collapseRows(rows, 3, new Set(['b0']))[0].rows.length).toBe(7)
  })
})
