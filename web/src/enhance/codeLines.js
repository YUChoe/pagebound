// 코드블록 줄 단위 처리 (spec/features/F9-code-blocks.md 2절 {3,5-7}, 3.1절 줄 앵커)
// hljs HTML(<span class=…>…</span>만 포함)을 줄마다 나눈다. 여러 줄에 걸친 span은 줄 끝에서 닫고 다음 줄에서 다시 연다.
export function splitLines(html) {
  const lines = []
  const open = []
  let current = ''
  const TOKEN = /(<span[^>]*>)|(<\/span>)|(\n)|([^<\n]+)/g
  for (const m of html.matchAll(TOKEN)) {
    if (m[1]) {
      open.push(m[1])
      current += m[1]
    } else if (m[2]) {
      open.pop()
      current += m[2]
    } else if (m[3]) {
      lines.push(current + '</span>'.repeat(open.length))
      current = open.join('')
    } else {
      current += m[4]
    }
  }
  lines.push(current + '</span>'.repeat(open.length))
  return lines
}

// "3,5-7" → Set{3,5,6,7}
export function parseRanges(spec) {
  const out = new Set()
  for (const part of (spec ?? '').split(',')) {
    const m = /^\s*(\d+)\s*(?:-\s*(\d+))?\s*$/.exec(part)
    if (!m) continue
    const from = Number(m[1])
    const to = Math.min(Number(m[2] ?? m[1]), from + 10000)
    for (let i = from; i <= to; i++) out.add(i)
  }
  return out
}

// <code> 내용을 줄 span으로 바꾼다. 강조 번호는 블록 안 순번(1부터), data-line은 표시 번호(start 반영)
export function applyLines(block, codeEl) {
  if (block.dataset.split === '1') return
  const start = Number(block.dataset.start) || 1
  const marked = parseRanges(block.dataset.highlight)
  codeEl.innerHTML = splitLines(codeEl.innerHTML)
    .map((html, i) => `<span class="cb-line${marked.has(i + 1) ? ' cb-hl' : ''}" data-line="${start + i}">${html}</span>`)
    .join('')
  block.dataset.split = '1'
}

// #code-{n}-L{line}
export function parseLineAnchor(hash) {
  const m = /^#?code-(\d+)-L(\d+)$/.exec(hash ?? '')
  return m ? { block: Number(m[1]), line: Number(m[2]) } : null
}
