// 줄 단위 diff (LCS). 충돌 대화상자용. 너무 크면 null (비교 없이 양쪽 표시)
const MAX_CELLS = 4_000_000

// 결과: [{ type: 'same' | 'del' | 'add', text }] — del = 서버에만, add = 내 쪽에만
export function diffLines(server, mine) {
  const a = server.split('\n')
  const b = mine.split('\n')
  if (a.length * b.length > MAX_CELLS) return null
  const n = a.length
  const m = b.length
  const lcs = Array.from({ length: n + 1 }, () => new Uint32Array(m + 1))
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      lcs[i][j] = a[i] === b[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1])
    }
  }
  const out = []
  let i = 0
  let j = 0
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      out.push({ type: 'same', text: a[i] })
      i++
      j++
    } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
      out.push({ type: 'del', text: a[i++] })
    } else {
      out.push({ type: 'add', text: b[j++] })
    }
  }
  while (i < n) out.push({ type: 'del', text: a[i++] })
  while (j < m) out.push({ type: 'add', text: b[j++] })
  return out
}
