<script setup lang="ts">
/**
 * 分类管理对话框（A23 / A24 / A26）。
 *
 * 从 `SkillCatalogView.vue` 抽出，2026-09-19 重做为**主从布局**：
 * 左侧是分类清单（封面缩略图 + 占用数 + 已上传标记），右侧是常驻的编辑面板。
 *
 * 重做前的问题：清单与「新增/修改」表单上下堆叠成一张长表单，新增和修改共用同一组输入框，
 * 管理员既看不出「我现在是在改哪一条」，也看不到已上传的封面到底长什么样。
 *
 * 现在的分工：
 * - 左栏：一眼看完所有分类，每行都有封面缩略图与占用数；行内可进入修改、删除。
 * - 右栏：**常驻**的编辑面板，默认「新增」态；点左栏某行的「修改」切到该行并高亮。
 *   新增与修改仍共用输入框（保持既有钩子与行为），但标题、按钮文案、取消入口随模式变化。
 *
 * **封面是三条写路径里唯一被整体收敛到编辑面板的**（2026-09-19 需求）：
 * 换封面、清空封面都只做本地标记，点「保存修改」时才由 `confirmImageChanges`
 * 做一次最终确认再提交。此前清空是清单行上的即时操作 —— 点一下当场删对象并刷新，
 * 误点没有反悔余地，而它改的又是直接决定桌面端精选场景卡片观感的线上数据。
 *
 * 父组件持有词表本身（列表筛选也要用），这里只接收、不缓存。
 */
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, Download, ImagePlus, Pencil, Plus, Trash2, Undo2, X } from 'lucide-vue-next'

import {
  createSkillCategory,
  deleteSkillCategory,
  deleteSkillCategoryImage,
  fetchSkillCategoryImage,
  updateSkillCategory,
  uploadSkillCategoryImage,
} from '../../api/admin'
import {
  expertCategoryDisplayName,
  invalidateExpertCategories,
  type ExpertCategoryDefinition,
} from '../../views/admin/expertCategories'
import { skillAdminErrorMessage } from '../../views/admin/skillAdminErrors'
import { downloadSkillImageBlob, skillImageFileName } from '../../utils/skillDownload'
import { confirmImageChanges } from '../../utils/imageChangeConfirm'

/** 封面文件规则，与技能图标保持一致 */
const ICON_MIME_TYPES = ['image/png', 'image/jpeg', 'image/webp', 'image/svg+xml']
/** 与后端 gateway.skills.icon-max-bytes 默认值 4 MB 对齐 */
const MAX_ICON_BYTES = 4 * 1024 * 1024

const props = defineProps<{
  visible: boolean
  /** 当前作用域的完整词表；父组件持有，这里只读 */
  categories: ExpertCategoryDefinition[]
  /** 词表作用域：技能用 SKILL，专家与专家团共用 EXPERT */
  scope: 'SKILL' | 'EXPERT'
  /** 页面名词（技能 / 专家 / 专家团），用于文案 */
  noun: string
  /** 包名词（技能包 / 专家包） */
  packageNoun: string
  /** 是否启用封面上传：只有专家分类有精选场景卡片 */
  coverEnabled: boolean
  /** 服务端错误码文案的作用域 */
  errorScope: { noun: string; packageNoun: string }
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  /** 词表被改动（新增 / 改名 / 删除 / 换封面）后触发，父组件据此重新拉取 */
  (e: 'changed'): void
  /** 删除的分类若正被筛选，父组件需要退出该筛选 */
  (e: 'category-removed', name: string): void
}>()

const form = reactive({ name: '', nameEn: '' })
const saving = ref(false)
const deletingCode = ref('')
const downloadingCode = ref('')
/** 封面预览地址：code → objectURL（与技能图标一样只拉一次） */
const imageUrls = ref<Record<string, string>>({})
/**
 * 正在改名的分类 code；'' 表示「新增」态。
 *
 * 新增与修改共用输入框，但语义不同：改名时名称是**写库值**，
 * 后端会在同一事务里把仍带旧名的包迁移到新名下，所以面板里必须提示这件事。
 */
const editingCode = ref('')
/**
 * 修改态下新选的封面文件（还没上传）。
 *
 * 与技能图标的语义一致：`pendingImageFile` 只表示「本地待上传」，
 * 已上传的封面一律走 `imageUrls` 的 objectURL，两者不能混用 ——
 * 否则「有没有待上传文件」和「线上有没有封面」会互相覆盖。
 */
const pendingImageFile = ref<File | null>(null)
/** 待上传封面的本地预览地址（只在选中文件后存在） */
const pendingImagePreviewUrl = ref('')
/**
 * 「待清空封面」标记：点编辑面板里的「清空封面」只置位，不碰后端。
 *
 * 此前清空是左栏清单行上的即时操作，点一下当场删对象并刷新列表 —— 误点没有反悔余地。
 * 现在与「换封面」一起收进编辑面板，都等到点「保存修改」时才提交。
 */
const pendingImageClear = ref(false)

const displayName = (category: ExpertCategoryDefinition) => expertCategoryDisplayName(category)
/** 当前正在编辑的那条分类（新增态为 null） */
const editingCategory = computed(
  () => props.categories.find((category) => category.code === editingCode.value) ?? null,
)
const isEditing = computed(() => editingCode.value !== '')

/**
 * 编辑面板里封面槽位要显示的图：本地待上传优先，其次已上传，最后占位。
 *
 * 与左栏缩略图共用 `imageUrls` 缓存，所以不会为同一张图发两次请求。
 * 标记为待清空时不再回显已上传的图 —— 预览要反映「保存之后的样子」。
 */
const editorCoverUrl = computed(() => {
  if (!props.coverEnabled || !editingCode.value) return ''
  if (pendingImageClear.value) return ''
  return pendingImagePreviewUrl.value || imageUrls.value[editingCode.value] || ''
})

/** 左栏摘要：让管理员不用点开就知道词表规模与占用情况 */
const summary = computed(() => ({
  total: props.categories.length,
  inUse: props.categories.filter((category) => category.usageCount > 0).length,
}))

function open(): void {
  startCreate()
  emit('update:visible', true)
  void loadImagePreviews()
}

/** 切到「新增」态：清空输入框并取消行高亮 */
function startCreate(): void {
  editingCode.value = ''
  form.name = ''
  form.nameEn = ''
  // 新增态没有封面槽位，丢掉可能残留的待上传文件，避免它跟着下一个分类提交
  clearPendingImage()
  // 待清空标记同理：它是「某一条分类」的状态，跟着切换到别的分类会张冠李戴
  pendingImageClear.value = false
}

/** 切到某条分类的「修改」态：预填当前名称，code 不可改（稳定标识，改名后筛选仍靠它） */
function startEdit(category: ExpertCategoryDefinition): void {
  clearPendingImage()
  pendingImageClear.value = false
  editingCode.value = category.code
  form.name = category.name
  form.nameEn = category.nameEn
}

function close(): void {
  clearPendingImage()
  pendingImageClear.value = false
  releaseImageUrls()
  emit('update:visible', false)
}

async function submit(): Promise<void> {
  const name = form.name.trim()
  if (!name) {
    ElMessage.warning('请填写分类名称。')
    return
  }
  const editing = editingCode.value
  // 封面只在修改态可选，所以待上传文件 / 待清空标记必然对应 `editing` 这条分类
  const coverFile = editing ? pendingImageFile.value : null
  const coverClear = editing ? pendingImageClear.value : false
  /*
   * 封面的**最终确认**：换图与清空都在这一刻之前只是本地标记，
   * 这里列出本次改动让操作者确认一次。选择「返回修改」时不改任何状态，
   * 线上封面原样保留 —— 这正是把破坏性操作从清单行收进编辑面板的意义。
   */
  if (coverFile || coverClear) {
    const changes = [coverClear ? `清空「${name}」的封面` : `替换「${name}」的封面`]
    const confirmed = await confirmImageChanges(changes)
    if (!confirmed) return
  }
  saving.value = true
  try {
    if (editing) {
      await updateSkillCategory(props.scope, editing, {
        name,
        nameEn: form.nameEn.trim() || null,
      })
      // 封面的两条写路径（换图 / 清空）与改名不在同一个事务里，放在改名之后：
      // 改名失败时不该留下一个「封面变了但名字没换」的半成品状态。
      // 两者互斥：`onImagePicked` 会复位清空标记，`markImageClear` 会丢掉待上传文件。
      if (coverClear) await deleteSkillCategoryImage(props.scope, editing)
      else if (coverFile) await uploadSkillCategoryImage(props.scope, editing, coverFile)
    } else {
      await createSkillCategory({
        kind: props.scope,
        name,
        nameEn: form.nameEn.trim() || null,
      })
    }
    // 新增或改名后必须失效缓存，否则页面会拿旧词表渲染，刚改的分类看不见
    invalidateExpertCategories(props.scope)
    emit('changed')
    if (coverClear && editing) {
      ElMessage.success(`已修改分类「${name}」并清空封面。`)
    } else if (coverFile && editing) {
      ElMessage.success(`已修改分类「${name}」并更新封面。`)
    } else {
      ElMessage.success(editing ? `已修改分类「${name}」。` : `已新增分类「${name}」。`)
    }
    // 提交后回到「新增」态：连续录入多个分类时不必手动清空
    startCreate()
  } catch (error) {
    ElMessage.error(skillAdminErrorMessage(error, props.errorScope))
  } finally {
    saving.value = false
  }
}

/**
 * 删除分类。分类下仍有包时后端返回 409 并带上占用的包标识，这里先确认再提交，
 * 失败时把后端说明（含包名）直接展示，管理员据此知道该改哪一个包。
 */
async function remove(category: ExpertCategoryDefinition): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `删除后新建或编辑${props.noun}时将不再提供该分类。分类下仍有包时无法删除。`,
      `确认删除分类「${displayName(category)}」`,
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  deletingCode.value = category.code
  try {
    await deleteSkillCategory(props.scope, category.code)
    // 删除后必须失效缓存，否则刚删的分类还会留在下拉里
    invalidateExpertCategories(props.scope)
    emit('changed')
    // 被删掉的分类若正被筛选，父组件回到「全部」以免停在空列表
    emit('category-removed', category.name)
    // 删掉的正好是正在编辑的那条 → 回到新增态，避免面板停在一条不存在的记录上
    if (editingCode.value === category.code) startCreate()
    ElMessage.success(`已删除分类「${displayName(category)}」。`)
  } catch (error) {
    ElMessage.error(skillAdminErrorMessage(error, props.errorScope))
  } finally {
    deletingCode.value = ''
  }
}

/**
 * 标记「清空封面」—— 只置位，不碰后端。
 *
 * 2026-09-19 前这里是左栏清单行上的即时删除（点一下当场删对象并刷新列表）。
 * 现在收进编辑面板，与「换封面」对称：标记 → 可撤销 → 点「保存修改」时统一确认并提交。
 */
function markImageClear(): void {
  // 已选新文件时撤销「清空」没有意义：新文件会直接覆盖旧封面
  clearPendingImage()
  pendingImageClear.value = true
}

/** 撤销「待清空封面」标记，回到原状态（不碰后端） */
function undoImageClear(): void {
  pendingImageClear.value = false
}

/**
 * 把已上传的封面下载到本地。
 *
 * 复用左栏缩略图已经拉过的 objectURL 太脆（objectURL 不能直接落盘），
 * 这里重新取一次字节并即时下载；封面只有几十 KB，不值得为它做缓存复用。
 */
async function downloadImage(category: ExpertCategoryDefinition): Promise<void> {
  if (downloadingCode.value) return
  downloadingCode.value = category.code
  try {
    const blob = await fetchSkillCategoryImage(props.scope, category.code)
    downloadSkillImageBlob(skillImageFileName(category.code, 'cover', blob), blob)
    ElMessage.success(`「${displayName(category)}」的封面已开始下载。`)
  } catch (error) {
    ElMessage.error(skillAdminErrorMessage(error, props.errorScope))
  } finally {
    downloadingCode.value = ''
  }
}

/* ---------------- 封面待上传文件（修改态） ---------------- */

/** 释放待上传封面的本地预览地址 */
function clearPendingImage(): void {
  if (pendingImagePreviewUrl.value) {
    if (typeof URL.revokeObjectURL === 'function') {
      URL.revokeObjectURL(pendingImagePreviewUrl.value)
    }
    pendingImagePreviewUrl.value = ''
  }
  pendingImageFile.value = null
}

/** 取消选择待上传封面：只丢掉本地文件，已上传的封面不动 */
function cancelPendingImage(): void {
  clearPendingImage()
}

/** 释放单个分类的封面地址 */
function releaseImage(code: string): void {
  const url = imageUrls.value[code]
  if (url && typeof URL.revokeObjectURL === 'function') URL.revokeObjectURL(url)
  const next = { ...imageUrls.value }
  delete next[code]
  imageUrls.value = next
}

/** 取分类封面的预览地址：与技能图标一样用 fetch + createObjectURL（只拉一次） */
async function loadImagePreview(category: ExpertCategoryDefinition): Promise<void> {
  if (!category.hasImage || imageUrls.value[category.code]) return
  try {
    const blob = await fetchSkillCategoryImage(props.scope, category.code)
    imageUrls.value = { ...imageUrls.value, [category.code]: URL.createObjectURL(blob) }
  } catch {
    // 分类封面是可选资源：拉取失败不影响词表展示，回退到占位徽标
  }
}

/** 打开「管理分类」时把已有封面各拉一次，管理员据此确认改的是哪一张 */
async function loadImagePreviews(): Promise<void> {
  // 技能分类没有封面（见 coverEnabled），因此不请求封面字节
  if (!props.coverEnabled) return
  await Promise.all(props.categories.map((category) => loadImagePreview(category)))
}

/** 释放已生成的封面预览地址（关闭对话框时调用） */
function releaseImageUrls(): void {
  for (const url of Object.values(imageUrls.value)) {
    if (typeof URL.revokeObjectURL === 'function') URL.revokeObjectURL(url)
  }
  imageUrls.value = {}
}

/** 选中封面文件：先本地校验（与技能图标同一套规则），只暂存不提交 */
function onImagePicked(event: Event): void {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  input.value = ''
  if (!file) return
  if (!ICON_MIME_TYPES.includes(file.type)) {
    ElMessage.error('分类封面只支持 PNG、JPG、WebP 或 SVG 格式。')
    return
  }
  if (file.size > MAX_ICON_BYTES) {
    ElMessage.error('分类封面不能超过 4 MB。')
    return
  }
  clearPendingImage()
  pendingImageFile.value = file
  // 选了新文件就不再需要「清空」——新封面会直接覆盖旧图
  pendingImageClear.value = false
  if (typeof URL.createObjectURL === 'function') {
    pendingImagePreviewUrl.value = URL.createObjectURL(file)
  }
}

// 词表刷新后（新增/改名/删除），把还在编辑的那条同步成最新值，
// 否则面板里会留着改名前的旧文案。
watch(
  () => props.categories,
  (rows) => {
    if (!editingCode.value) return
    const current = rows.find((row) => row.code === editingCode.value)
    if (!current) return
    form.name = current.name
    form.nameEn = current.nameEn
  },
)

defineExpose({ open })
</script>

<template>
  <el-dialog
    :model-value="visible"
    :title="`管理${noun}分类`"
    width="min(960px, calc(100vw - 32px))"
    :teleported="false"
    destroy-on-close
    @update:model-value="(value: boolean) => (value ? emit('update:visible', true) : close())"
  >
    <p class="field-help" data-test="category-manage-hint">
      分类是后端词表，新增后即可在新建{{ noun }}时选用；改名会同时把在用该分类的
      {{ packageNoun }}改到新名称下。分类下仍有包时不能删除，请先改归类或移除这些包。
    </p>

    <div class="category-manager">
      <!-- 左栏：分类清单 -->
      <section class="category-manager__list" aria-label="分类清单">
        <header class="category-manager__list-head">
          <h3>
            分类清单
            <span class="category-manager__count" data-test="category-manage-summary">
              {{ summary.total }} 个 · {{ summary.inUse }} 个在用
            </span>
          </h3>
        </header>

        <ul v-if="categories.length" class="category-manage-list" data-test="category-manage-list">
          <li
            v-for="category in categories"
            :key="category.code"
            class="category-manage-item"
            :class="{ 'category-manage-item--active': editingCode === category.code }"
            :data-test="`category-manage-item-${category.code}`"
          >
            <!-- 封面缩略图：管理视图里直接看到已上传的图，而不是只显示一个「有/无」 -->
            <span
              v-if="coverEnabled"
              class="category-manage-item__cover"
              :data-test="`category-manage-cover-${category.code}`"
            >
              <img
                v-if="imageUrls[category.code]"
                :src="imageUrls[category.code]"
                :alt="`${displayName(category)}封面`"
              />
              <span v-else class="category-manage-item__badge" aria-hidden="true">
                {{ (category.name || '?').slice(0, 1) }}
              </span>
            </span>

            <div class="category-manage-item__body">
              <span class="category-manage-item__name">{{ displayName(category) }}</span>
              <span class="category-manage-item__meta">
                <span class="category-manage-item__count" :data-test="`category-manage-count-${category.code}`">
                  {{ category.usageCount }} 个包
                </span>
                <span v-if="category.hasImage" class="category-manage-item__flag">已上传封面</span>
              </span>
            </div>

            <div class="category-manage-item__actions">
              <!--
                清单行只保留进入修改与删除。封面下载统一放在右栏修改态，
                避免分类列表和修改页同时出现重复入口；清空封面也只在修改态
                标记后随「保存修改」确认提交。
              -->
              <el-button
                size="small"
                text
                :data-test="`category-manage-edit-${category.code}`"
                @click="startEdit(category)"
              >
                <Pencil :size="14" aria-hidden="true" />
                修改
              </el-button>
              <el-button
                size="small"
                text
                type="danger"
                :loading="deletingCode === category.code"
                :disabled="category.usageCount > 0"
                :title="category.usageCount > 0 ? '该分类下仍有包，请先改归类' : ''"
                :data-test="`category-manage-delete-${category.code}`"
                @click="remove(category)"
              >
                <Trash2 :size="14" aria-hidden="true" />
                删除
              </el-button>
            </div>
          </li>
        </ul>
        <p v-else class="admin-empty" data-test="category-manage-empty">
          还没有分类，请在右侧新增。
        </p>
      </section>

      <!-- 右栏：常驻编辑面板，默认「新增」态 -->
      <section class="category-manager__editor" aria-label="分类编辑">
        <header class="category-manager__editor-head">
          <h3>
            <template v-if="isEditing">
              <Pencil :size="14" aria-hidden="true" />
              修改分类
            </template>
            <template v-else>
              <Plus :size="14" aria-hidden="true" />
              新增分类
            </template>
          </h3>
          <el-button
            v-if="isEditing"
            size="small"
            text
            data-test="category-form-cancel-edit"
            @click="startCreate"
          >
            取消修改
          </el-button>
        </header>

        <p v-if="isEditing" class="field-help" data-test="category-form-edit-hint">
          正在修改「{{ editingCategory ? displayName(editingCategory) : '' }}」。改名后，仍在使用该分类的
          {{ packageNoun }}会一起改到新名称下，不需要逐个调整。
        </p>

        <div class="category-manager__fields">
          <el-form-item label="分类名称（中文，必填）">
            <el-input
              v-model="form.name"
              data-test="category-form-name"
              maxlength="32"
              placeholder="例如：文档与数据"
            />
          </el-form-item>
          <el-form-item label="分类名称（英文，可选）">
            <el-input
              v-model="form.nameEn"
              data-test="category-form-name-en"
              maxlength="32"
              placeholder="例如：Documents & Data"
            />
          </el-form-item>
        </div>

        <!--
          封面操作全部收在修改态里：换封面、清空封面都只做本地标记，
          点「保存修改」时统一确认后才提交。
          封面直接决定桌面端精选场景卡片的观感，清单里误点即生效没有反悔余地。
        -->
        <section
          v-if="coverEnabled && isEditing"
          class="category-manager__cover"
          data-test="category-form-cover"
        >
          <h4>
            <ImagePlus :size="14" aria-hidden="true" />
            分类封面（可选）
          </h4>
          <p class="field-help">
            PNG / JPG / WebP / SVG，不超过 4 MB。用于桌面端精选场景卡片，建议 3:1 宽幅；
            换封面或清空封面都需要点「保存修改」才会真正生效。
          </p>
          <div class="category-manager__cover-row">
            <span class="category-manager__cover-frame" data-test="category-form-cover-preview">
              <img v-if="editorCoverUrl" :src="editorCoverUrl" alt="分类封面预览" />
              <span v-else class="category-manager__cover-empty">未上传</span>
            </span>
            <div class="category-manager__cover-actions">
              <input
                type="file"
                accept="image/png,image/jpeg,image/webp,image/svg+xml"
                class="category-manager__cover-input"
                data-test="category-form-cover-file"
                @change="onImagePicked"
              />
              <el-button
                v-if="pendingImagePreviewUrl"
                size="small"
                data-test="category-form-cover-cancel"
                @click="cancelPendingImage"
              >
                取消选择
              </el-button>
              <el-button
                v-if="editingCategory?.hasImage"
                size="small"
                text
                :loading="downloadingCode === editingCategory.code"
                data-test="category-form-cover-download"
                @click="downloadImage(editingCategory)"
              >
                <Download :size="14" aria-hidden="true" />
                下载
              </el-button>
              <!-- 清空只置标记，点「保存修改」时才提交；期间可撤销 -->
              <el-button
                v-if="editingCategory?.hasImage && !pendingImageClear"
                size="small"
                text
                type="danger"
                data-test="category-form-cover-clear"
                @click="markImageClear"
              >
                <X :size="14" aria-hidden="true" />
                清空封面
              </el-button>
              <el-button
                v-if="editingCategory?.hasImage && pendingImageClear"
                size="small"
                text
                data-test="category-form-cover-undo-clear"
                @click="undoImageClear"
              >
                <Undo2 :size="14" aria-hidden="true" />
                撤销清空
              </el-button>
            </div>
          </div>
          <p
            v-if="pendingImageClear"
            class="category-manager__pending"
            data-test="category-form-cover-pending"
          >
            封面已标记为清空，点「保存修改」后生效；在此之前随时可以撤销。
          </p>
        </section>

        <div class="category-manager__editor-actions">
          <el-button
            type="primary"
            data-test="category-form-submit"
            :loading="saving"
            @click="submit"
          >
            <Check :size="14" aria-hidden="true" />
            {{ isEditing ? '保存修改' : '新增分类' }}
          </el-button>
        </div>
      </section>
    </div>

    <template #footer>
      <el-button data-test="category-form-cancel" @click="close">关闭</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
/* ---------- 主从布局 ---------- */
.category-manager {
  display: grid;
  gap: var(--space-4);
  grid-template-columns: minmax(0, 1.25fr) minmax(0, 1fr);
}

@media (max-width: 760px) {
  .category-manager {
    grid-template-columns: minmax(0, 1fr);
  }
}

.category-manager__list,
.category-manager__editor {
  min-width: 0;
  padding: var(--space-3);
  background: var(--app-surface-subtle);
  border: 1px solid var(--app-border);
  border-radius: var(--radius-md);
}

.category-manager__list {
  display: flex;
  max-height: 420px;
  flex-direction: column;
}

.category-manager__list-head h3,
.category-manager__editor-head h3 {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  margin: 0 0 var(--space-2);
  color: var(--app-fg);
  font-size: var(--text-body);
}

.category-manager__list-head h3 svg,
.category-manager__editor-head h3 svg {
  color: var(--app-primary);
}

.category-manager__count {
  margin-left: var(--space-2);
  color: var(--app-muted);
  font-size: var(--text-caption);
  font-weight: 400;
}

.category-manager__editor {
  display: flex;
  flex-direction: column;
}

.category-manager__editor-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
}

.category-manager__fields {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.category-manager__editor-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: auto;
  padding-top: var(--space-3);
}

/* ---------- 清单 ---------- */
.category-manage-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin: 0;
  padding: 0;
  overflow-y: auto;
  list-style: none;
}

.category-manage-item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2);
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: var(--radius-sm);
}

/* 正在编辑的那一行给出明确高亮，解决「不知道在改哪一条」 */
.category-manage-item--active {
  border-color: var(--app-primary-border);
  box-shadow: inset 3px 0 0 var(--app-primary);
}

.category-manage-item__cover {
  display: inline-flex;
  width: 40px;
  height: 40px;
  flex: 0 0 40px;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: var(--app-primary-soft);
  border: 1px solid var(--app-primary-border);
  border-radius: var(--radius-sm);
  color: var(--app-primary);
  font-weight: 700;
}

.category-manage-item__cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.category-manage-item__body {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.category-manage-item__name {
  overflow: hidden;
  color: var(--app-fg);
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.category-manage-item__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-1);
  color: var(--app-muted);
  font-size: var(--text-caption);
}

.category-manage-item__flag {
  padding: 1px 6px;
  color: var(--app-primary);
  background: var(--app-primary-soft);
  border: 1px solid var(--app-primary-border);
  border-radius: var(--radius-xs);
}

.category-manage-item__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 2px;
}

/* ---------- 编辑面板里的封面槽位 ---------- */
.category-manager__cover {
  margin-top: var(--space-2);
  padding-top: var(--space-2);
  border-top: 1px dashed var(--app-border);
}

.category-manager__cover h4 {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  margin: 0 0 var(--space-1);
  color: var(--app-fg);
  font-size: var(--text-caption);
}

.category-manager__cover h4 svg {
  color: var(--app-primary);
}

.category-manager__cover-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
}

/* 3:1 宽幅：与桌面端精选场景卡片的展示比例一致，管理员据此判断构图是否被裁 */
.category-manager__cover-frame {
  display: inline-flex;
  width: 144px;
  height: 48px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: var(--app-primary-soft);
  border: 1px solid var(--app-primary-border);
  border-radius: var(--radius-sm);
}

.category-manager__cover-frame img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.category-manager__cover-empty {
  color: var(--app-muted);
  font-size: var(--text-caption);
}

.category-manager__cover-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
}

.category-manager__cover-input {
  color: var(--app-muted-strong);
  font-size: var(--text-caption);
}

/* 待提交的封面改动提示：让「已标记但还没生效」这件事在界面上始终可见 */
.category-manager__pending {
  margin: var(--space-2) 0 0;
  padding: 6px 10px;
  color: var(--app-warning-fg, #b45309);
  font-size: var(--text-caption);
  background: var(--app-warning-soft, rgba(245, 158, 11, 0.12));
  border-radius: var(--radius-sm);
}
</style>
