// 역사 화면 diff 표시: 변경 주변 context줄만 남기고 같은 줄 묶음은 접는다 (spec/features/F7-common.md 2절)
// rows: [{ type, oldLine, newLine, oldText, newText }] → [{ key, rows } | { key, hidden }]
export function collapseRows(rows, context = 3, expanded = new Set()) {
  const keep = rows.map(() => false)
  rows.forEach((r, i) => {
    if (r.type === 'equal') return
    for (let j = Math.max(0, i - context); j <= Math.min(rows.length - 1, i + context); j++) keep[j] = true
  })
  const blocks = []
  let i = 0
  while (i < rows.length) {
    const start = i
    const visible = keep[i]
    while (i < rows.length && keep[i] === visible) i++
    const key = `b${start}`
    // 짧은 같은 줄 묶음(2줄 이하)이나 펼친 묶음은 그대로 보인다
    if (visible || i - start <= 2 || expanded.has(key)) blocks.push({ key, rows: rows.slice(start, i) })
    else blocks.push({ key, hidden: i - start })
  }
  return blocks
}
