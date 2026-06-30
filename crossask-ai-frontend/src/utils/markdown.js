import { marked } from 'marked'
import DOMPurify from 'dompurify'

marked.setOptions({
  breaks: true,        // 单换行也转 <br>
  gfm: true             // GitHub Flavored Markdown
})

/** 渲染 Markdown 并清洗 XSS。 */
export function renderMarkdown(text) {
  if (!text) return ''
  const raw = marked.parse(text)
  return DOMPurify.sanitize(raw)
}
