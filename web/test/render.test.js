import { describe, expect, it } from 'vitest'
import { renderMarkdown } from '../src/markdown/render.js'
import { parseInfo } from '../src/markdown/codeInfo.js'
import { resolve } from '../src/markdown/wikiPaths.js'
import { enhanceSections } from '../src/enhance/sections.js'
import { enhanceCodeBlocks } from '../src/enhance/codeBlocks.js'

function dom(html) {
  const el = document.createElement('div')
  el.innerHTML = html
  return el
}

describe('위키 링크', () => {
  it('존재 여부에 따라 레드 링크, 코드 안은 링크 아님', () => {
    const md = '[[nems/통계]] [[없는문서|표시]] [[/하위]]\n\n```bash\nif [[ -f x ]]; then :; fi\n```\n\n`[[ -z $a ]]`'
    const { html } = renderMarkdown(md, { path: 'home', links: { 'nems/통계': { exists: true, path: 'nems/통계' } } })
    const el = dom(html)
    const links = [...el.querySelectorAll('a.wikilink')]
    expect(links.map((a) => a.dataset.path)).toEqual(['nems/통계', '없는문서', 'home/하위'])
    expect(links[0].classList.contains('red')).toBe(false)
    expect(links[1].classList.contains('red')).toBe(true)
    expect(links[1].textContent).toBe('표시')
    expect(links[0].getAttribute('href')).toBe('/w/nems/%ED%86%B5%EA%B3%84')
    expect(el.querySelector('pre').textContent).toContain('[[ -f x ]]')
  })

  it('상대 경로 해석은 서버 WikiPaths와 동일', () => {
    expect(resolve('nems/고도화', '/하위')).toBe('nems/고도화/하위')
    expect(resolve('nems/고도화', '../')).toBe('nems')
    expect(resolve('nems/고도화', '../형제')).toBe('nems/형제')
    expect(resolve('a/b', '../../../x')).toBe('')
  })
})

describe('헤딩 번호', () => {
  it('서버 MarkdownAnalyzerTest와 같은 번호', () => {
    const md = '개요\n====\n본문\n\n```\n# 코드 안\n```\n\n## 설정\n### 설정파일\n설정\n-----\n# 로드맵\n'
    const { sections } = renderMarkdown(md)
    expect(sections.map((s) => s.title)).toEqual(['개요', '설정', '설정파일', '설정', '로드맵'])
    expect(sections.map((s) => s.number)).toEqual(['1.', '1.1.', '1.1.1.', '1.2.', '2.'])
    expect(sections[3].id).toBe('h-설정-2')
    expect(renderMarkdown('## A\n#### B\n## C\n').sections.map((s) => s.number)).toEqual(['1.', '1.1.', '2.'])
  })

  it('섹션 접기 구조', () => {
    const el = dom(renderMarkdown('# A\n본문A\n## B\n본문B\n# C\n본문C').html)
    enhanceSections(el)
    const top = [...el.children].filter((c) => c.classList.contains('sec'))
    expect(top).toHaveLength(2)
    expect(top[0].querySelector('.sec-body .sec h2').textContent).toContain('B')
    top[0].querySelector('.sec-fold').click()
    expect(top[0].classList.contains('folded')).toBe(true)
  })
})

describe('콜아웃·태스크리스트·표', () => {
  it('변환', () => {
    const { html } = renderMarkdown('> [!WARNING]\n> 백업 필수\n\n- [x] 완료\n- [ ] 미완\n\n|a|b|\n|-|-|\n|1|2|')
    const el = dom(html)
    const q = el.querySelector('blockquote')
    expect(q.className).toBe('callout callout-warning')
    expect(q.textContent.trim()).toBe('백업 필수')
    expect(el.querySelectorAll('li.task-item input[type=checkbox]')).toHaveLength(2)
    expect(el.querySelector('li.task-item input').checked).toBe(true)
    expect(el.querySelector('.table-wrap > table')).not.toBeNull()
  })
})

describe('코드블록', () => {
  it('info string: 첫 토큰이 속성이면 언어 미지정', () => {
    expect(parseInfo('title="/etc/hosts" collapsed')).toMatchObject({ lang: '', title: '/etc/hosts', collapsed: true })
    expect(parseInfo('java title="A.java" {3,5-7} start=120 nolinenums'))
      .toMatchObject({ lang: 'java', title: 'A.java', highlight: '3,5-7', start: 120, lineNumbers: false })
  })

  it('접기 상태가 localStorage에 유지되고 문서 기본값보다 우선', () => {
    localStorage.clear()
    const md = '```bash title="a.sh" collapsed\necho 1\n```\n\n```\nplain\n```'
    const el = dom(renderMarkdown(md).html)
    const ctl = enhanceCodeBlocks(el, 7)
    const [a, b] = el.querySelectorAll('.codeblock')
    expect(a.classList.contains('is-collapsed')).toBe(true)
    expect(b.classList.contains('is-collapsed')).toBe(false)
    a.querySelector('.cb-toggle').click()
    expect(localStorage.getItem('pb.fold.7.a.sh')).toBe('false')

    const again = dom(renderMarkdown(md).html)
    enhanceCodeBlocks(again, 7)
    expect(again.querySelector('.codeblock').classList.contains('is-collapsed')).toBe(false)
    ctl.setAll(true)
    const third = dom(renderMarkdown(md).html)
    enhanceCodeBlocks(third, 7)
    expect([...third.querySelectorAll('.codeblock')].every((x) => x.classList.contains('is-collapsed'))).toBe(true)
  })

  it('26줄 이상은 높이 제한, 줄 번호 거터', () => {
    const code = Array.from({ length: 30 }, (_, i) => `line ${i}`).join('\n')
    const el = dom(renderMarkdown('```\n' + code + '\n```').html)
    enhanceCodeBlocks(el, 1)
    const block = el.querySelector('.codeblock')
    expect(block.classList.contains('is-clamped')).toBe(true)
    expect(block.querySelector('.cb-more').textContent).toBe('5줄 더 보기')
    expect(block.querySelectorAll('.cb-gutter span')).toHaveLength(30)
  })
})
