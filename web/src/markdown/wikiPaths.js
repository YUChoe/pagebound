// 서버 io.github.yuchoe.pagebound.core.page.WikiPaths 와 같은 규칙

export function normalize(path) {
  if (!path) return ''
  return path
    .split('/')
    .map((s) => s.trim())
    .filter((s) => s.length > 0)
    .join('/')
}

export function join(parent, slug) {
  const p = normalize(parent)
  return p ? `${p}/${slug}` : slug
}

export function parentOf(path) {
  const p = normalize(path)
  const i = p.lastIndexOf('/')
  return i < 0 ? '' : p.substring(0, i)
}

export function lastSegment(path) {
  const p = normalize(path)
  return p.substring(p.lastIndexOf('/') + 1)
}

// "/하위" → 하위 문서, "../" → 상위, "../형제" → 형제, 그 외 절대 경로. 루트 위로 가면 ''
export function resolve(currentPath, target) {
  const t = (target ?? '').trim()
  if (t.startsWith('/')) return normalize(join(currentPath, normalize(t)))
  if (t.startsWith('../') || t === '..') {
    let base = normalize(currentPath)
    let rest = t
    while (rest.startsWith('../') || rest === '..') {
      if (!base) return ''
      base = parentOf(base)
      rest = rest === '..' ? '' : rest.substring(3)
    }
    const r = normalize(rest)
    return r ? normalize(join(base, r)) : base
  }
  return normalize(t)
}

// 경로 세그먼트별 URL 인코딩 (슬래시 유지)
export function encodePath(path) {
  return normalize(path).split('/').map(encodeURIComponent).join('/')
}
