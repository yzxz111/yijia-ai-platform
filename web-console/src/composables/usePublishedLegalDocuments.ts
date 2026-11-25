import { onMounted, ref } from 'vue'

import { getPublishedLegalDocument, type LegalDocumentKind } from '../api/platform'
import { CONSENT_VERSION, LEGAL_DOCUMENTS } from '../consent'

type LegalDocumentView = (typeof LEGAL_DOCUMENTS)[LegalDocumentKind]

/**
 * 服务条款与隐私政策的已发布版本，从平台服务加载，
 * 这样管理员修改后无需重新构建控制台即可生效。当 API 不可达或尚未发布任何文档时，
 * 回退到本次构建内置的文本。
 *
 * 缓存放在模块级，使登录页复选框与文档对话框共用同一次请求和
 * 同一个版本串——创建账号的请求所携带的版本，必须正是
 * 用户实际读到的版本。
 */
const versions = ref<Record<LegalDocumentKind, string>>({
  TERMS: CONSENT_VERSION,
  PRIVACY: CONSENT_VERSION,
})
const documents = ref<Record<LegalDocumentKind, LegalDocumentView>>({
  TERMS: LEGAL_DOCUMENTS.TERMS,
  PRIVACY: LEGAL_DOCUMENTS.PRIVACY,
})
const loaded = ref(false)
let inFlight: Promise<void> | null = null

export async function loadPublishedLegalDocuments(): Promise<void> {
  if (inFlight) return inFlight
  inFlight = (async () => {
    try {
      const kinds: LegalDocumentKind[] = ['TERMS', 'PRIVACY']
      const results = await Promise.allSettled(kinds.map((kind) => getPublishedLegalDocument(kind)))
      const nextVersions = { ...versions.value }
      const nextDocuments = { ...documents.value }
      results.forEach((result, index) => {
        if (result.status !== 'fulfilled') return
        const kind = kinds[index]
        const published = result.value
        const bundled = LEGAL_DOCUMENTS[kind]
        if (published.version) nextVersions[kind] = published.version
        nextDocuments[kind] = {
          ...bundled,
          title: published.title || bundled.title,
          sections: published.content?.sections?.length
            ? published.content.sections
            : bundled.sections,
          placeholder: published.content?.placeholder || bundled.placeholder,
        }
      })
      versions.value = nextVersions
      documents.value = nextDocuments
    } catch {
      // 绝不能让文档抓取失败拖垮登录页：内置文本保持原位，
      // 而若已发布文本确实不同，服务端会拒绝过期版本。
    } finally {
      loaded.value = true
      inFlight = null
    }
  })()
  return inFlight
}

/** 创建账号的请求必须为该文档携带的版本串。 */
export function consentVersion(kind: LegalDocumentKind = 'TERMS'): string {
  return versions.value[kind]
}

export function usePublishedLegalDocuments() {
  onMounted(() => { void loadPublishedLegalDocuments() })

  return {
    documents,
    versions,
    loaded,
    reload: loadPublishedLegalDocuments,
    consentVersion,
  }
}
