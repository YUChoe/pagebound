// 서버 스니펫 강조 표식(\u0002 시작, \u0003 끝)을 이스케이프 후 <mark>로 변환 (spec/features/F11-search.md 9절)

const ESCAPE = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }

export function escapeHtml(text) {
  return String(text).replace(/[&<>"']/g, (c) => ESCAPE[c])
}

export function highlight(text) {
  return escapeHtml(text).replace(/\u0002/g, '<mark>').replace(/\u0003/g, '</mark>')
}

// 제목 강조: 검색어(대소문자 무시) 위치에 <mark>
export function highlightTerms(text, terms) {
  const safe = escapeHtml(text)
  const words = terms.map((t) => t.trim()).filter(Boolean).map(escapeHtml)
  if (!words.length) return safe
  const pattern = new RegExp(words.map((w) => w.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|'), 'gi')
  return safe.replace(pattern, (m) => `<mark>${m}</mark>`)
}

// 질의에서 강조할 검색어만 추출 (필터·제외어 제외)
export function queryTerms(q) {
  const out = []
  for (const m of (q ?? '').matchAll(/-?"[^"]*"|\S+/g)) {
    const t = m[0]
    if (t.startsWith('-') || /^(tag|path|in|author):/.test(t)) continue
    out.push(t.startsWith('"') && t.endsWith('"') && t.length > 1 ? t.slice(1, -1) : t)
  }
  return out
}
