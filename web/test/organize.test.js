import { describe, expect, it } from 'vitest'
import { highlight, highlightTerms, queryTerms } from '../src/search/highlight.js'
import { dropZone, moveRequest } from '../src/tree/dnd.js'

describe('검색 강조', () => {
  it('표식을 mark로, 나머지 HTML은 이스케이프', () => {
    expect(highlight('<b>x</b> \u0002본문\u0003 끝')).toBe('&lt;b&gt;x&lt;/b&gt; <mark>본문</mark> 끝')
    expect(highlightTerms('Altibase <라이선스>', ['altibase'])).toBe('<mark>Altibase</mark> &lt;라이선스&gt;')
  })

  it('질의에서 강조 대상만 추출', () => {
    expect(queryTerms('altibase "라이선스 만료" -apache tag:nems path:a')).toEqual(['altibase', '라이선스 만료'])
  })
})

describe('트리 드롭', () => {
  it('영역 판정', () => {
    expect(dropZone(2, 20)).toBe('before')
    expect(dropZone(10, 20)).toBe('inside')
    expect(dropZone(18, 20)).toBe('after')
  })

  it('형제 사이 위치는 끌고 있는 항목을 뺀 순서 기준', () => {
    const siblings = ['a', 'b', 'c']
    expect(moveRequest('a', 'c', 'after', siblings)).toEqual({ parentPath: '', position: 2 })
    expect(moveRequest('c', 'a', 'before', siblings)).toEqual({ parentPath: '', position: 0 })
    expect(moveRequest('x/y', 'b', 'inside', siblings)).toEqual({ parentPath: 'b', position: null })
  })

  it('자기 자신·하위로는 이동 불가', () => {
    expect(moveRequest('a', 'a', 'inside', ['a'])).toBeNull()
    expect(moveRequest('a', 'a/b', 'inside', ['a/b'])).toBeNull()
  })
})
