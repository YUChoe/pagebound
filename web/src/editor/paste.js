// 붙여넣기/드롭 파일 규칙 (spec/features/F1-editor.md 2절)

export const MAX_FILES = 10
export const MAX_BYTES = 50 * 1024 * 1024

const GENERIC = /^image\.(png|jpe?g|gif|webp|bmp)$/i
const EXT = { 'image/png': 'png', 'image/jpeg': 'jpg', 'image/gif': 'gif', 'image/webp': 'webp', 'image/bmp': 'bmp' }

function stamp(d) {
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`
}

// 파일명 없는 이미지(클립보드 스크린샷은 보통 image.png)는 paste-YYYYMMDD-HHmmss.ext
export function pasteName(file, now = new Date()) {
  const name = file.name ?? ''
  if (name && !GENERIC.test(name)) return name
  return `paste-${stamp(now)}.${EXT[file.type] ?? (name.split('.').pop() || 'png')}`
}

export function isImage(file) {
  return (file.type ?? '').startsWith('image/')
}

export function placeholder(name, image) {
  return `${image ? '!' : ''}[업로드 중 ${name}…]()`
}

export function fileMarkdown(info, image) {
  return image ? `![${info.filename}](${info.url})` : `[${info.filename}](${info.url})`
}
