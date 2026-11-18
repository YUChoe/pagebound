// 문서 내 강조 (spec/features/F11-search.md 5절 P1): ?hl=검색어 → 본문 텍스트 일치에 <mark class="hl">,
// 첫 일치를 감싼 접힌 섹션·코드블록을 펼치고 그 위치로 스크롤 (F9 4.1 연동)
export function highlightInPage(container, terms) {
  const words = terms.map((t) => t.trim()).filter((t) => t.length > 0)
  if (!words.length) return 0
  const pattern = new RegExp(words.map((w) => w.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|'), 'gi')
  const walker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT, {
    acceptNode: (n) => (n.parentElement?.closest('script, style, mark, .cb-header, .sec-fold') ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT),
  })
  const nodes = []
  while (walker.nextNode()) nodes.push(walker.currentNode)
  let count = 0
  for (const node of nodes) {
    const text = node.textContent
    pattern.lastIndex = 0
    if (!pattern.test(text)) continue
    pattern.lastIndex = 0
    const frag = document.createDocumentFragment()
    let at = 0
    for (const m of text.matchAll(pattern)) {
      frag.append(text.slice(at, m.index))
      const mark = document.createElement('mark')
      mark.className = 'hl'
      mark.textContent = m[0]
      frag.append(mark)
      at = m.index + m[0].length
      count++
    }
    frag.append(text.slice(at))
    node.replaceWith(frag)
  }
  const first = container.querySelector('mark.hl')
  if (first) reveal(first)
  return count
}

function reveal(el) {
  for (let sec = el.closest('.sec.folded'); sec; sec = sec.parentElement?.closest('.sec.folded')) {
    sec.classList.remove('folded')
    const fold = sec.querySelector(':scope > :is(h1, h2, h3, h4, h5, h6) > .sec-fold')
    if (fold) fold.textContent = '▾'
  }
  const block = el.closest('.codeblock.is-collapsed')
  if (block) {
    block.classList.remove('is-collapsed')
    const toggle = block.querySelector('.cb-toggle')
    if (toggle) toggle.textContent = '▾'
  }
  el.scrollIntoView?.({ block: 'center' })
}
