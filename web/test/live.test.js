// 실데이터 검증 (spec/phases/M2-read.md 작업 5·7): PB_SERVER=http://localhost:8080 npx vitest run test/live.test.js
// 1) 서버(flexmark) 섹션 분할 = 클라이언트(markdown-it) 섹션 분할
// 2) 렌더 + 보강(섹션·코드블록) 파이프라인 예외 0
import { describe, expect, it } from 'vitest'
import { renderMarkdown } from '../src/markdown/render.js'
import { enhanceSections } from '../src/enhance/sections.js'
import { enhanceCodeBlocks } from '../src/enhance/codeBlocks.js'

const SERVER = process.env.PB_SERVER

async function json(url) {
  const res = await fetch(SERVER + url)
  if (!res.ok) throw new Error(`${res.status} ${url}`)
  return res.json()
}

describe.skipIf(!SERVER)('실데이터 (PB_SERVER)', () => {
  it('전체 문서 섹션 일치 + 렌더 예외 0', async () => {
    const paths = []
    const walk = (nodes) => nodes.forEach((n) => { paths.push(n.path); walk(n.children) })
    walk(await json('/api/v1/tree?depth=20'))

    const mismatches = []
    const errors = []
    const timings = []
    for (const path of paths) {
      const q = encodeURIComponent(path)
      const page = await json(`/api/v1/pages/by-path?path=${q}`)
      const outline = await json(`/api/v1/pages/outline?path=${q}`)
      const t0 = performance.now()
      try {
        const r = renderMarkdown(page.content, { path, links: page.links })
        const el = document.createElement('div')
        el.innerHTML = r.html
        enhanceSections(el)
        enhanceCodeBlocks(el, page.id)
        const server = outline.map((s) => `${s.number} ${s.title}`)
        const client = r.sections.map((s) => `${s.number} ${s.title}`)
        if (JSON.stringify(server) !== JSON.stringify(client)) mismatches.push({ path, server, client })
      } catch (e) {
        errors.push(`${path}: ${e.message}`)
      }
      timings.push([performance.now() - t0, path])
    }
    timings.sort((a, b) => b[0] - a[0])
    console.log(`문서 ${paths.length}, 섹션 불일치 ${mismatches.length}, 예외 ${errors.length}`)
    console.log('jsdom 렌더 상위 3:', timings.slice(0, 3).map(([ms, p]) => `${p} ${ms.toFixed(0)}ms`).join(', '))
    mismatches.slice(0, 5).forEach((m) => console.log(JSON.stringify(m)))
    expect(errors).toEqual([])
    expect(mismatches).toEqual([])
  })
})
