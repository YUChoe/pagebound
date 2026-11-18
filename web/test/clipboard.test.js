import { describe, expect, it } from 'vitest'
import { htmlToMarkdown, imageName, isRichHtml, isTsv, isUrl, tsvToMarkdown } from '../src/editor/clipboard.js'
import { renderMarkdown } from '../src/markdown/render.js'

describe('붙여넣기 P1 (F1 2절)', () => {
  it('엑셀 3x3 → 마크다운 표, 탭 들여쓰기 코드는 표 아님', () => {
    const tsv = '이름\t역할\t비고\n홍길동\tEDITOR\ta|b\n김철수\tVIEWER\t\n'
    expect(isTsv(tsv)).toBe(true)
    expect(tsvToMarkdown(tsv)).toBe('| 이름 | 역할 | 비고 |\n| --- | --- | --- |\n| 홍길동 | EDITOR | a\\|b |\n| 김철수 | VIEWER |  |\n')
    expect(isTsv('if x:\n\treturn 1\n')).toBe(false)
  })

  it('URL 판별, 코드 에디터 HTML은 일반 텍스트', () => {
    expect(isUrl(' https://a.b/c?d=1 ')).toBe(true)
    expect(isUrl('see https://a.b')).toBe(false)
    expect(isRichHtml('<div style="font-family:Consolas"><span>const a = 1</span></div>')).toBe(false)
    expect(isRichHtml('<p>문단 <strong>굵게</strong></p>')).toBe(true)
  })

  it('HTML → 마크다운, 이미지 src 수집 (data:, http)', () => {
    const html = '<h2>제목</h2><p>본문 <a href="https://x.y">링크</a></p><img src="data:image/png;base64,iVBO"><img src="https://img.test/a/b.jpg"><img src="/local.png">'
      + '<table><tr><th>a</th><th>b</th></tr><tr><td>1</td><td>2</td></tr></table>'
    const { markdown, images } = htmlToMarkdown(html)
    expect(markdown).toContain('## 제목')
    expect(markdown).toContain('[링크](https://x.y)')
    expect(markdown).toContain('| a | b |')
    expect(images).toEqual(['data:image/png;base64,iVBO', 'https://img.test/a/b.jpg'])
    expect(imageName('https://img.test/a/b.jpg', 0)).toBe('b.jpg')
    expect(imageName('data:image/jpeg;base64,xx', 1, new Date(2026, 0, 2, 3, 4, 5))).toBe('paste-20260102-030405-2.jpg')
  })

  it('각주 렌더 (F5 P1)', () => {
    const { html } = renderMarkdown('본문[^1]\n\n[^1]: 각주 내용')
    expect(html).toContain('class="footnote-ref"')
    expect(html).toContain('각주 내용')
  })
})
