import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderMarkdown } from '../src/markdown/render.js'
import { api } from '../src/api.js'
import { clearLinkPreviewCache, collectLinks, enhanceLinkPreviews, parseLabel } from '../src/enhance/linkPreview.js'

const MR = 'http://gitlab.test/grp/nea-snmp/-/merge_requests/13'

function dom(md) {
  const el = document.createElement('div')
  el.innerHTML = renderMarkdown(md).html
  return el
}

describe('링크 미리보기 표시 형태 (F10 2절)', () => {
  beforeEach(() => clearLinkPreviewCache())

  it('단독 줄은 카드, 문장·목록·<URL>은 칩, [텍스트](URL)은 장식, 코드 안은 제외', () => {
    const el = dom(`${MR}\n\n문장 속 ${MR}/diffs 링크\n\n- ${MR}#note_1\n\n<${MR}>\n\n[리뷰 요청](${MR})\n\n\`${MR}\`\n\n\`\`\`\n${MR}\n\`\`\`\n`)
    expect(collectLinks(el).map((l) => l.mode)).toEqual(['card', 'chip', 'chip', 'chip', 'decorate'])
    expect(collectLinks(dom('빌드는 build.sh 실행'))).toEqual([])
    // 문단 안이라도 줄 전체가 URL이면 카드 (breaks: true)
    expect(collectLinks(dom(`설명 줄\n${MR}\n다음 줄 ${MR} 끝`)).map((l) => l.mode)).toEqual(['card', 'chip'])
  })

  it('비로그인: 서버 호출 없이 URL 라벨만', async () => {
    const spy = vi.spyOn(api, 'linkPreviews')
    const el = dom(`앞 ${MR} 뒤\n\nhttp://gitlab.test/a/b/commit/0123456789abcdef`)
    await enhanceLinkPreviews(el, { authenticated: false })
    expect(spy).not.toHaveBeenCalled()
    expect([...el.querySelectorAll('.lp-label')].map((e) => e.textContent)).toEqual(['nea-snmp!13', 'b@01234567'])
    expect(el.querySelector('.lp-state')).toBeNull()
    expect(parseLabel('https://example.com/about')).toBeNull()
  })

  it('로그인: 결과를 텍스트로만 넣고 실패는 일반 링크 유지', async () => {
    vi.spyOn(api, 'linkPreviews').mockResolvedValue([
      { url: MR, kind: 'merge_request', status: 'ok', data: { label: 'nea-snmp!13', title: '<img src=x onerror=alert(1)>', state: 'merged' } },
      { url: 'http://gitlab.test/g/p/-/issues/9', kind: 'issue', status: 'not_found', data: { label: 'p#9' } },
    ])
    const el = dom(`- ${MR}\n- http://gitlab.test/g/p/-/issues/9\n\n${MR}\n`)
    await enhanceLinkPreviews(el, { authenticated: true })
    expect(api.linkPreviews).toHaveBeenCalledTimes(1)
    const chip = el.querySelector('li a')
    expect(chip.querySelector('.lp-title').textContent).toBe('<img src=x onerror=alert(1)>')
    expect(chip.querySelector('img')).toBeNull()
    expect(chip.querySelector('.lp-state').textContent).toBe('Merged')
    const failed = el.querySelectorAll('li a')[1]
    expect(failed.textContent).toBe('http://gitlab.test/g/p/-/issues/9')
    expect(el.querySelector('.lp-card .lp-refresh')).not.toBeNull()
  })
})
