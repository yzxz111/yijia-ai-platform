<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Check, ChevronDown, Clock3, Gift, ArrowRight, Sparkles, Crown, Zap, Building2, Clapperboard } from 'lucide-vue-next'
import { ElMessage } from 'element-plus'
import { storeToRefs } from 'pinia'

import { quotaSummary, type QuotaSummary } from '../../api/gateway'
import { claimFreePlan, getFreePlanClaimStatus, isSecondDimension, quotaUnitLabel, type PackagePlan } from '../../api/platform'
import CheckoutDialog from '../../components/payment/CheckoutDialog.vue'
import TopErrorMessage from '../../components/feedback/TopErrorMessage.vue'
import { usePackageStore } from '../../stores/package'
import { isVideoModelCode, userModelLabel } from '../../utils/modelLabel'
import { userPlanDescription, userPlanName } from '../../utils/planCopy'

const packageStore = usePackageStore()
const { plans, loading } = storeToRefs(packageStore)
const quota = ref<QuotaSummary | null>(null)
const loadError = ref('')
const selectedPlan = ref<PackagePlan | null>(null)
const checkoutVisible = ref(false)
const claimingFree = ref(false)
const freeClaimed = ref(false)
let claimStatusSequence = 0

/**
 * 自 V80 起一个用户可以每个用量维度各持有一个生效套餐，所以「当前使用」可能有多个：
 * 用集合承接，否则买了视频套餐之后文本套餐会被标成未生效。旧网关不返回
 * activePackages 时回退到单个 activePackage。
 */
const activeCodes = computed(() => {
  const listed = quota.value?.activePackages?.length
    ? quota.value.activePackages
    : (quota.value?.activePackage ? [quota.value.activePackage] : [])
  return new Set(listed.map((item) => item.packageCode).filter(Boolean))
})
const queuedCodes = computed(() => new Set(quota.value?.queuedPackages.map((item) => item.packageCode) || []))
const number = new Intl.NumberFormat('zh-CN')

/**
 * 固定排序: 业务档次升序, 但企业版(ENTERPRISE)永远放在最后一列作为主推收尾.
 * 不依赖价格(会有小数/运营调价后错乱) 不依赖 name(会改名).
 *   1) FREE → 2) STARTER(入门) → 3) ADVANCED(高级) → 4) 视频套餐线(按档位升序) → 5) 其余未知套餐(按 priceAmount 升序) → 6) ENTERPRISE(企业版) 钉死最右
 * 上下架状态不影响排序位(未上架卡灰化后仍然占位, 管理员操作体验稳定).
 *
 * 视频套餐也进这张表, 而不是靠"未知档位按价格升序"兜底: 年付档(¥599)比高级视频档
 * (¥399)贵, 按价格排会把它插进两个不同档位中间, 月付/年付这一对就被拆开了.
 * 这里的序号与 platform.package_plan.display_order 一致(视频线 36/37 起, 再 40/50/60).
 */
const PLAN_TIER_ORDER: Record<string, number> = {
  FREE: 10,
  STARTER: 20,
  ADVANCED: 30,
  VIDEO_LITE: 36,
  VIDEO_LITE_ANNUAL: 37,
  VIDEO_STARTER: 40,
  VIDEO_CREATOR: 50,
  VIDEO_PRO: 60,
  // ENTERPRISE = 999, 在下面单独处理, 永远最后
}
const sortedPlans = computed<PackagePlan[]>(() => {
  const list = plans.value?.slice() || []
  return list.sort((a, b) => {
    // step1: ENTERPRISE 永远排最后(不管另一个是什么)
    if (a.code === 'ENTERPRISE') return 1
    if (b.code === 'ENTERPRISE') return -1
    // step2: 已知档位先按固定 tier
    const ta = PLAN_TIER_ORDER[a.code]
    const tb = PLAN_TIER_ORDER[b.code]
    if (ta != null && tb != null && ta !== tb) return ta - tb
    // step3: 一方命中已知 tier → 未知的夹在 ADVANCED 与 ENTERPRISE 之间
    if (ta != null) return -1
    if (tb != null) return 1
    // step4: 全部未知 → 按价格升序(保证稳定顺序, 数量多时不抖动)
    return (a.priceAmount || 0) - (b.priceAmount || 0)
  })
})

/**
 * 分类页签：文字套餐按积分计费、视频套餐按秒计费，两者的额度不是同一种东西。
 * 混在同一份列表里时，一句"积分额度"就会把视频套餐的 60 秒念成 60 积分，
 * 所以卡片和对比表都按维度分开放，一次只出现同一种额度单位。
 */
type PlanTabId = 'TEXT' | 'VIDEO'

const PLAN_TABS: { id: PlanTabId; label: string }[] = [
  { id: 'TEXT', label: '文字套餐' },
  { id: 'VIDEO', label: '视频套餐' },
]

const activeTab = ref<PlanTabId>('TEXT')

function isVideoPlan(plan: PackagePlan) {
  return isSecondDimension(plan.usageDimension)
}

const textPlans = computed(() => sortedPlans.value.filter(plan => !isVideoPlan(plan)))
const videoPlans = computed(() => sortedPlans.value.filter(plan => isVideoPlan(plan)))
/** 只有一类套餐时不摆页签：一个点进去空空如也的页签不如没有。 */
const showTabs = computed(() => textPlans.value.length > 0 && videoPlans.value.length > 0)
const tabPlans = computed<PackagePlan[]>(() => {
  if (!showTabs.value) return sortedPlans.value
  return activeTab.value === 'VIDEO' ? videoPlans.value : textPlans.value
})

function price(plan: PackagePlan) {
  return new Intl.NumberFormat('zh-CN', {
    style: 'currency',
    currency: plan.currency || 'CNY',
    minimumFractionDigits: 0,
    maximumFractionDigits: 2,
  }).format(plan.priceAmount / 100)
}

/**
 * tokensPerMinute 的维度随 usageDimension 变化：
 * TOKEN 套餐是每分钟积分数（TPM），VIDEO_SECOND/IMAGE 套餐是每分钟秒数（SPM）。
 * 视频套餐写成 TPM 会把"秒"读成"积分"，与卡片权益列表里的 SPM 自相矛盾。
 *
 * 注意：供应商成本与毛利属于内部成本结构，只出现在管理端（套餐版本页），
 * 用户端套餐列表不展示 estimatedCostAmount / estimatedMarginRatio / marginWarning。
 */
function speedLimitLabel(plan: PackagePlan) {
  return isSecondDimension(plan.usageDimension) ? 'SPM（秒）' : 'TPM（积分）'
}

function isFree(plan: PackagePlan) {
  return plan.kind === 'FREE_CLAIM'
}

/**
 * 是否已上架（有发布版本）。
 * - 管理员可以上下架套餐：后端以 "是否存在 versionId" 作为 "已发布版本(上架)" 的稳定判定字段
 *   (PackagePlan.versionId 为空字符串或 null 时 = 未发布 = 下架状态)
 * - 注意: 不能用 plan.name 或展示文案判断, 避免管理员改名后逻辑漂移
 */
function isPublished(plan: PackagePlan) {
  return Boolean(plan && plan.versionId)
}

/**
 * 是否主推套餐。
 * 优先级:
 *   1) 稳定业务标识 ENTERPRISE = 企业版 (用户明确要求主推企业版)
 *   2) 否则已上架套餐中价格最高的非免费付费套餐 (管理员上下架变动后的合理 fallback)
 * 绝不以 "倒数第二贵/中间价位" 这类脆弱的猜价策略作为主判断 —
 * 套餐数量或定价变动时会把错误的套餐打上推荐角标。
 */
function isHighlighted(plan: PackagePlan) {
  if (!plans.value?.length) return false
  if (plan.code === 'ENTERPRISE' && isPublished(plan)) return true
  // 若 ENTERPRISE 不存在或未上架 → 取已上架 & 已定价的付费套餐里最高档的一款作为推荐
  const candidates = plans.value.filter(p => !isFree(p) && isPublished(p) && p.priceAmount > 0)
  if (!candidates.length) return false
  const top = [...candidates].sort((a, b) => b.priceAmount - a.priceAmount)[0]
  return top?.code === plan.code
}

function paidDisabledReason(plan: PackagePlan) {
  if (queuedCodes.value.has(plan.code)) return '该套餐已在排队中，无需重复购买'
  if (activeCodes.value.has(plan.code) && (quota.value?.activePackage?.remainingTokens ?? 0) > 0) return '当前套餐仍有剩余额度，暂时无法重复购买'
  if (!isPublished(plan)) return '套餐版本未发布'
  if (plan.soldOut) return '套餐已售罄'
  if (plan.priceAmount <= 0) return '套餐暂未定价'
  return ''
}

function inventoryLabel(plan: PackagePlan) {
  const label = plan.stockTotal === null
    ? `不限库存 · 已售 ${number.format(plan.stockSold)}`
    : `剩余 ${number.format(plan.remainingStock || 0)} / ${number.format(plan.stockTotal)}`
  return plan.lowStock ? `库存偏低 · ${label}` : label
}

const freeUnavailable = computed(() => freeClaimed.value || plans.value.some(plan =>
  isFree(plan) && (activeCodes.value.has(plan.code) || queuedCodes.value.has(plan.code))))

/**
 * 单次请求最多能生成多少视频。网关接受 1..60 秒，
 * 其余一律拒绝（VideoGenerationService.validate -> invalid_video_request），
 * docs/接口文档.md 把 durationSeconds 记为 1-60。这是平台级上限，
 * 而不是某个套餐给予的权益——单个厂商别名可能只支持更窄的区间——所以
 * 文案只陈述可接受区间，不把它写成套餐权益。
 */
const VIDEO_REQUEST_SECONDS = '1–60 秒'

function planFeatures(plan: PackagePlan) {
  const unit = quotaUnitLabel(plan.usageDimension)
  const features: string[] = []
  // 视频套餐的秒数额度不在用户端展示：卡片上只说明计费方式，不写"买了多少秒"。
  // 折算单价（元/秒）同样不能写 —— 价格除以单价就能反推出秒数，等于没藏。
  if (isSecondDimension(plan.usageDimension)) features.push('按秒计费，按实际生成时长扣减')
  else features.push(`${number.format(plan.totalTokenQuota)} ${unit}额度`)
  // 套餐的 API 密钥上限不在用户端展示（字段仍在接口/数据库里，只隐藏前端渲染）。
  features.push(`RPM ${number.format(plan.requestsPerMinute)}`)
  // tokensPerMinute 同样带量纲：对视频套餐而言它是每秒的
  // 每分钟上限，标成 TPM 就是错的。
  features.push(`${unit === '秒' ? 'SPM' : 'TPM'} ${number.format(plan.tokensPerMinute)}`)
  features.push(`并发 ${plan.maxConcurrency}`)
  if (plan.dailyRequestLimit) features.push(`日请求上限 ${number.format(plan.dailyRequestLimit)}`)
  else features.push('日请求不限')
  // maxOutputTokens 不带量纲：V54 只把 usage_dimension 给了套餐的
  // 总额度及其按模型子额度，视频链路从不读取这一
  // 列——视频请求受自身时长与套餐的秒数
  // 余额约束。因此视频卡片只陈述它真正拥有的请求上限，
  // 而不宣传视频买家永远碰不到的 token 上限。
  // 按秒计费的套餐都不宣传 token 上限（视频与音频同此）。
  // 单次时长的具体区间只对视频有已文档化的平台上限（VIDEO_REQUEST_SECONDS）；
  // 音频三个能力的允许区间不同（转写可到 12 小时、换口型是 2–120 秒），
  // 因此音频卡片**不编造**一个区间，只说明它按秒计费。
  if (plan.usageDimension === 'VIDEO_SECOND') {
    features.push(`单次视频 ${VIDEO_REQUEST_SECONDS}`)
  } else if (isSecondDimension(plan.usageDimension)) {
    features.push('按实际用量计费（秒）')
  } else {
    features.push(plan.maxOutputTokens == null ? '输出不限' : `最大输出 ${number.format(plan.maxOutputTokens)} 积分`)
  }
  features.push(plan.models.length ? `${plan.models.length} 个可用模型` : '以发布版本为准')
  return features
}

function planIcon(plan: PackagePlan) {
  if (isFree(plan)) return Gift
  if (plan.code === 'STARTER') return Zap
  if (plan.code === 'ADVANCED') return Sparkles
  // 视频套餐是独立产品线，不是企业文本套餐。没有
  // 这一段它会悄悄继承企业版的图标与配色。
  if (isSecondDimension(plan.usageDimension)) return Clapperboard
  return Building2
}

/** 套餐档位 → 色轨音调（用于卡片左侧状态色轨 + 图标底色） */
function planTier(plan: PackagePlan) {
  if (isFree(plan)) return 'free'
  if (plan.code === 'STARTER') return 'starter'
  if (plan.code === 'ADVANCED') return 'advanced'
  if (isSecondDimension(plan.usageDimension)) return 'video'
  return 'enterprise'
}

const faqItems = [
  {
    question: '同一套餐可以重复购买吗？',
    answer: '同一套餐的当前额度未用完时不能重复购买；额度用完后可以继续购买，但同一套餐累计购买次数不能超过三次。',
  },
  {
    question: '套餐额度用完后怎么办？',
    answer: '套餐额度和余额会按系统计费规则优先使用。你可以在余额钱包、额度和调用日志页面查看剩余额度与实际消耗。',
  },
  {
    question: '每个套餐可以调用哪些模型？',
    answer: '卡片中的"可用模型"来自已发布的套餐版本配置。实际请求会按模型与渠道健康状态进行路由，切换渠道不会改变套餐权限。',
  },
  {
    question: '套餐退款规则是什么？',
    answer: '余额支付购买的套餐退款退回余额；微信或支付宝直接购买的套餐按原支付路径退款。余额充值不支持提现或退款。',
  },
]

type ComparisonRow = {
  label: string
  value: (plan: PackagePlan) => string
}

function fileSizeLabel(bytes: number) {
  if (!bytes || bytes <= 0) return '未限制'
  if (bytes >= 1024 * 1024 * 1024) return `${number.format(Math.round(bytes / (1024 * 1024 * 1024)))} GB`
  return `${number.format(Math.max(1, Math.round(bytes / (1024 * 1024))))} MB`
}

function modelCountLabel(plan: PackagePlan) {
  return plan.models.length ? `${plan.models.length} 个模型` : '以发布版本为准'
}

/**
 * 额度单元格自带单位，因为同一张表混用了量纲：同一
 * 列数字在 TOKEN 套餐下是积分、在 VIDEO_SECOND 套餐下是秒数——
 * 而秒数这一种刻意不向客户展示
 * （视频套餐只说明如何计费，不说明包含多少秒）。
 */
function quotaCell(plan: PackagePlan, value: number) {
  if (isSecondDimension(plan.usageDimension)) return '按秒计费'
  return `${number.format(value)} ${quotaUnitLabel(plan.usageDimension)}`
}

/** maxOutputTokens 是 token 上限，因此按秒计费的套餐改为陈述其请求上限。 */
function maxOutputCell(plan: PackagePlan) {
  if (plan.usageDimension === 'VIDEO_SECOND') return VIDEO_REQUEST_SECONDS
  if (isSecondDimension(plan.usageDimension)) return '不适用'
  return plan.maxOutputTokens == null ? '未限制' : quotaCell(plan, plan.maxOutputTokens)
}

const comparisonRows: ComparisonRow[] = [
  { label: '额度与计费', value: plan => quotaCell(plan, plan.totalTokenQuota) },
  { label: 'RPM', value: plan => number.format(plan.requestsPerMinute) },
  { label: '每分钟限速', value: plan => `${speedLimitLabel(plan)} ${number.format(plan.tokensPerMinute)}` },
  { label: '并发数', value: plan => number.format(plan.maxConcurrency) },
  { label: '单次最大输出', value: maxOutputCell },
  { label: '单文件大小', value: plan => fileSizeLabel(plan.maxFileSizeBytes) },
  { label: '可用模型', value: modelCountLabel },
  { label: '库存', value: plan => plan.soldOut ? '已售罄' : inventoryLabel(plan) },
]

async function claimFree() {
  if (freeUnavailable.value) return
  claimingFree.value = true
  try {
    await claimFreePlan()
    claimStatusSequence += 1
    freeClaimed.value = true
    ElMessage.success('免费套餐已领取，授权正在处理中。')
  } catch {
    ElMessage.error('免费套餐领取失败，请稍后重试。')
  } finally {
    claimingFree.value = false
  }
}

function openCheckout(plan: PackagePlan) {
  if (paidDisabledReason(plan)) return
  selectedPlan.value = plan
  checkoutVisible.value = true
}

async function refreshQuota() {
  try {
    quota.value = await quotaSummary()
  } catch {
    // 额度刷新延迟时，已成功的订单仍会显示在订单页。
  }
}

onMounted(async () => {
  const sequence = ++claimStatusSequence
  try {
    const [, summary, claimStatus] = await Promise.all([
      packageStore.loadPlans(),
      quotaSummary(),
      getFreePlanClaimStatus(),
    ])
    quota.value = summary
    // 任一维度的生效套餐是视频套餐就直接停在视频页签，省得用户自己找。
    const activePlans = sortedPlans.value.filter(plan => activeCodes.value.has(plan.code))
    if (activePlans.some(isVideoPlan)) activeTab.value = 'VIDEO'
    if (sequence === claimStatusSequence) freeClaimed.value = claimStatus.claimed
  } catch {
    if (sequence === claimStatusSequence) {
      loadError.value = '套餐信息加载失败，请稍后重试。'
    }
  }
})


</script>

<template>
  <section class="plans-page">
    <div class="page-container">
      <!-- ============ 页面标题: 展示字 + 等宽 eyebrow, 克制字重 ============ -->
      <header class="plans-head">
        <div class="plans-head__title">
          <p class="plans-head__eyebrow">PLANS · 套餐管理</p>
          <h1>选择你的额度方案</h1>
          <p class="plans-head__sub">所有额度与权限均为已发布套餐版本的真实配置，购买后即时生效。</p>
        </div>
      </header>

      <el-skeleton v-if="loading" :rows="7" animated />
      <TopErrorMessage v-else-if="loadError" :message="loadError" />

      <template v-else>
        <!-- ============ 分类页签: 文字套餐(积分) / 视频套餐(秒), 额度单位不同不混排 ============ -->
        <div v-if="showTabs" class="plans-tabs" role="tablist" aria-label="套餐分类">
          <button
            v-for="tab in PLAN_TABS"
            :key="tab.id"
            type="button"
            role="tab"
            class="plans-tab"
            :class="{ 'plans-tab--active': activeTab === tab.id }"
            :aria-selected="activeTab === tab.id"
            :data-test="`plans-tab-${tab.id}`"
            @click="activeTab = tab.id"
          >{{ tab.label }}</button>
        </div>

        <!-- ============ 套餐卡片: 等宽 Grid + 左侧状态色轨 ============ -->
        <div v-if="tabPlans.length" class="plans-grid" role="list">
          <article
            v-for="plan in tabPlans"
            :key="plan.code"
            class="plan-card"
            :class="{
              'plan-card--featured': isHighlighted(plan),
              'plan-card--current': activeCodes.has(plan.code),
              'plan-card--offline': !isPublished(plan),
              [`plan-card--tier-${planTier(plan)}`]: true,
            }"
            role="listitem"
            data-test="plan-card"
            :data-plan-code="plan.code"
          >
          <!-- 卡片头部: 左对齐, 图标 36×36 + 标题 + 角标(右上角小方块) -->
          <div class="plan-card__head">
            <div class="plan-meta">
              <div class="plan-icon" :class="[`plan-icon--tier-${planTier(plan)}`, { 'plan-icon--featured': isHighlighted(plan) }]">
                <component :is="planIcon(plan)" :size="18" />
              </div>
              <div class="plan-meta__text">
                <h2 class="plan-name">{{ userPlanName(plan.code, plan.name) }}</h2>
                <p class="plan-desc">{{ userPlanDescription(plan.code, plan.description) }}</p>
              </div>
            </div>
            <!-- 角标: 小巧简约 10px 字, 不浮夸 -->
            <div class="plan-badges">
              <!-- 未上架 = 最高优先级展示(管理员运营态需要一眼识别出来) -->
              <span v-if="!isPublished(plan)" class="tag tag--offline">
                未上架
              </span>
              <span v-else-if="activeCodes.has(plan.code)" class="tag tag--current">
                <Check :size="10" :stroke-width="3" />
                <span>当前使用</span>
              </span>
              <span v-else-if="queuedCodes.has(plan.code)" class="tag tag--queue">
                <Clock3 :size="10" />
                <span>排队中</span>
              </span>
              <span v-else-if="isHighlighted(plan)" class="tag tag--featured">
                <Crown :size="10" />
                <span>推荐</span>
              </span>
            </div>
          </div>

          <!-- 分割线 -->
          <div class="divider"></div>

          <!-- 价格区: 左对齐, 数字+周期一行, 字重克制 -->
          <div class="plan-price">
            <span class="plan-price__currency" v-if="!isFree(plan)">¥</span>
            <span class="plan-price__amount" :class="{ 'plan-price__amount--free': isFree(plan) }">
              {{ isFree(plan) ? '免费' : (plan.priceAmount / 100).toLocaleString('zh-CN', { maximumFractionDigits: 2 }) }}
            </span>
            <span class="plan-price__period">/ {{ plan.durationDays }} 天</span>
          </div>

          <!-- 额度 2 列: 等宽左对齐, 数字>字 视觉权重
               视频套餐不显示秒数额度(只写计费方式); 套餐的 API 密钥上限同样不在用户端展示.
               字段都仍在接口与数据库里, 这里只是不渲染. -->
          <div class="plan-quota">
            <div class="quota-item">
              <span class="quota-item__val">{{ isSecondDimension(plan.usageDimension) ? '按秒计费' : number.format(plan.totalTokenQuota) }}</span>
              <span class="quota-item__lbl">{{ isSecondDimension(plan.usageDimension) ? '计费方式' : `${quotaUnitLabel(plan.usageDimension)}额度` }}</span>
            </div>
            <div class="quota-item">
              <span class="quota-item__val">{{ plan.dailyRequestLimit ? number.format(plan.dailyRequestLimit) : '不限' }}</span>
              <span class="quota-item__lbl">日请求</span>
            </div>
          </div>

          <div class="divider"></div>

          <!-- 权益列表: 对勾小图标(12px), 每行固定 28px 高度, 文字不换行 → 等高 -->
          <ul class="plan-features">
            <li v-for="(feat, i) in planFeatures(plan)" :key="i" class="feature-item">
              <Check :size="12" :stroke-width="3" class="feature-item__check" />
              <span class="feature-item__text">{{ feat }}</span>
            </li>
          </ul>

          <!-- 可用模型: 细灰底圆角小标签, 高度统一, 多余省略 -->
          <div v-if="plan.models.length" class="plan-models">
            <span v-for="model in plan.models.slice(0, 6)" :key="model.modelCode" class="model-chip">
              {{ userModelLabel(model.modelCode) }}
            </span>
            <span v-if="plan.models.length > 6" class="model-chip model-chip--more">
              +{{ plan.models.length - 6 }}
            </span>
          </div>

          <!-- 库存状态: 次要, 弱化 -->
          <div v-if="!isFree(plan) && plan.stockTotal !== null"
               class="plan-stock"
               :class="{ 'plan-stock--danger': plan.soldOut, 'plan-stock--warn': plan.lowStock && !plan.soldOut }">
            {{ plan.soldOut ? '已售罄' : inventoryLabel(plan) }}
          </div>

          <!-- 底部按钮: 统一尺寸 36px, 贴底 -->
          <div class="plan-footer">
            <p v-if="isFree(plan) && !plan.versionId" class="plan-disabled">套餐版本未发布</p>
            <p v-else-if="!isFree(plan) && paidDisabledReason(plan)" class="plan-disabled">{{ paidDisabledReason(plan) }}</p>

            <button
              v-if="isFree(plan)"
              type="button"
              class="btn"
              data-test="claim-free"
              :class="freeUnavailable || activeCodes.has(plan.code) ? 'btn--gray' : 'btn--outline'"
              :disabled="freeUnavailable || !plan.versionId || claimingFree"
              @click="claimFree"
            >
              <Gift :size="14" v-if="!freeUnavailable" />
              {{ freeUnavailable ? '已领取' : '免费领取' }}
            </button>
            <button
              v-else
              type="button"
              class="btn"
              data-test="buy-plan"
              :class="{
                'btn--primary': isHighlighted(plan) && !paidDisabledReason(plan),
                'btn--outline': !isHighlighted(plan) && !paidDisabledReason(plan),
                'btn--gray': Boolean(paidDisabledReason(plan)),
              }"
              :disabled="Boolean(paidDisabledReason(plan))"
              @click="openCheckout(plan)"
            >
              {{ queuedCodes.has(plan.code) ? '排队中' : activeCodes.has(plan.code) && paidDisabledReason(plan) ? '当前使用' : paidDisabledReason(plan) || '立即购买' }}
              <ArrowRight v-if="!paidDisabledReason(plan)" :size="14" />            </button>
          </div>
        </article>
        </div>
        <p v-else class="plans-empty">该分类下暂无已发布的套餐版本，请稍后再来查看。</p>

        <!-- ============ 套餐对比表格: 只对比当前页签的套餐 ============ -->
        <section class="block-section" v-if="tabPlans.length">
          <header class="section-title">
            <h3>套餐对比</h3>
            <p>基于当前已发布套餐版本的限制与权限。</p>
          </header>
          <div class="table-wrapper">
            <table class="compare-table">
              <thead>
                <tr>
                  <th class="col-func">功能</th>
                  <th v-for="plan in tabPlans" :key="`th-${plan.code}`"
                      :class="{ 'col-featured': isHighlighted(plan) }">
                    {{ userPlanName(plan.code, plan.name) }}
                  </th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in comparisonRows" :key="row.label">
                  <td class="col-func">{{ row.label }}</td>
                  <td v-for="plan in tabPlans" :key="`${row.label}-${plan.code}`"
                      :class="{ 'col-featured': isHighlighted(plan) }">
                    {{ row.value(plan) }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <!-- ============ FAQ ============ -->
        <section class="block-section">
          <header class="section-title">
            <h3>常见问题</h3>
            <p>关于套餐、额度和支付规则的说明。</p>
          </header>
          <div class="faq-grid">
            <details v-for="item in faqItems" :key="item.question" class="faq-item">
              <summary class="faq-item__sum">
                <span class="faq-item__q">{{ item.question }}</span>
                <ChevronDown :size="16" class="faq-item__arrow" />
              </summary>
              <p class="faq-item__a">{{ item.answer }}</p>
            </details>
          </div>
        </section>
      </template>
    </div>

    <CheckoutDialog
      v-if="selectedPlan"
      v-model="checkoutVisible"
      :plan="selectedPlan"
      @paid="refreshQuota"
    />
  </section>
</template>

<style scoped>
/* ==========================================================================
   后台风格套餐页 — 工业实用 / 克制专业（与内容安全、系统运维同语言）
   - 页面容器: 最大宽度 1180px 水平居中
   - 卡片/按钮圆角统一 12px, 标签 6px
   - 色: 冷灰白底 + 白卡 + 细灰分割线; 主色低饱和天蓝, 强调色暖陶橙(仅主推)
   - 左侧状态色轨: 免费=蓝灰 / 入门=蓝 / 高级=紫蓝 / 企业=暖陶橙
   ========================================================================== */

.plans-page {
  width: 100%;
  padding: 28px 0 48px;
  background: transparent;
}

/* ------- 最大宽度容器 ------- */
.page-container {
  max-width: 1180px;
  margin: 0 auto;
  padding: 0 24px;
  box-sizing: border-box;
}

/* ------- 页面标题: 展示字 + 等宽 eyebrow（与其他页面同构） ------- */
.plans-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 16px;
}
.plans-head__eyebrow {
  margin: 0 0 4px;
  font-family: var(--font-mono);
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.18em;
  color: var(--app-muted);
}
.plans-head h1 {
  margin: 0;
  font-family: var(--font-display);
  font-size: 27px;
  font-weight: 400;
  letter-spacing: -0.02em;
  color: var(--app-fg);
}
.plans-head__sub {
  margin: 6px 0 0;
  max-width: 62ch;
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--app-muted-strong);
}

/* ==========================================================================
   分类页签: 文字套餐 / 视频套餐
   - 分段控件语言: 浅灰底槽 + 选中白底, 与卡片同圆角族
   - 高度 30px: 比卡片按钮(36px)矮一档, 不与购买动作抢视觉
   ========================================================================== */
.plans-tabs {
  display: inline-flex;
  gap: 4px;
  margin-bottom: 20px;
  padding: 3px;
  background: var(--app-surface-muted);
  border: 1px solid var(--app-border);
  border-radius: 8px;
}
.plans-tab {
  display: inline-flex;
  align-items: center;
  height: 30px;
  padding: 0 14px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  font-family: inherit;
  font-size: 13px;
  font-weight: 500;
  line-height: 1;
  color: var(--app-muted-strong);
  cursor: pointer;
  transition: background-color 160ms ease, color 160ms ease;
}
.plans-tab:hover { color: var(--app-fg); }
.plans-tab:focus-visible {
  outline: 2px solid #c6ddff;
  outline-offset: 1px;
}
.plans-tab--active {
  color: var(--app-fg);
  background: var(--app-surface);
  box-shadow: var(--app-shadow-xs);
}

/* 该分类下没有已发布版本: 说明清楚, 不留空白网格 */
.plans-empty {
  margin: 0;
  padding: 40px 20px;
  text-align: center;
  font-size: 13px;
  line-height: 1.6;
  color: var(--app-muted);
  background: var(--app-surface);
  border: 1px dashed var(--app-border-strong);
  border-radius: 12px;
}

/* ==========================================================================
   套餐卡片 Grid: 4 列/3 列 等宽, 统一 24px gap
   ========================================================================== */
.plans-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(256px, 1fr));
  gap: 24px;
  align-items: start;
}

/* ---------------- 卡片 ---------------- */
.plan-card {
  position: relative;
  display: flex;
  flex-direction: column;
  padding: 20px;
  padding-left: 23px;
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-left: 3px solid var(--app-border-strong);
  border-radius: 12px;
  box-sizing: border-box;
  /* 最小高度: 保证不同内容量卡片视觉等长, 顶部对齐, 按钮贴底 */
  min-height: 520px;
}

/* 档位色轨：左侧 3px 由档位决定（与顶部统计带同构） */
.plan-card--tier-free       { border-left-color: var(--app-border-strong); }
.plan-card--tier-starter    { border-left-color: var(--ops-processing-rail); }
.plan-card--tier-advanced   { border-left-color: var(--app-primary); }
.plan-card--tier-enterprise { border-left-color: var(--app-accent); }
/* 视频套餐是独立产品线：按秒计费，用独立色轨与图文提示区分 */
.plan-card--tier-video      { border-left-color: var(--ops-processing-rail); }

.plan-video-note {
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--app-text-secondary);
}

/* 推荐卡: 主色描边 + 浅投影（比普通卡略重, 不夸张） */
.plan-card--featured {
  border-color: var(--app-primary);
  border-left-width: 3px;
  border-left-color: var(--app-primary);
  box-shadow: var(--app-shadow-xs);
}
.plan-card--current {
  border-color: var(--app-primary-soft);
}
/* 未上架: 整体灰化 + 边界降级, 按钮区由模板统一禁用 */
.plan-card--offline {
  border-color: var(--app-border);
  background: var(--app-surface-subtle);
  color: var(--app-muted);
}
.plan-card--offline .plan-name,
.plan-card--offline .plan-desc,
.plan-card--offline .plan-price__amount,
.plan-card--offline .quota-item__val,
.plan-card--offline .plan-section-title,
.plan-card--offline .feature-item,
.plan-card--offline .models-row__name,
.plan-card--offline .models-row__quota {
  color: var(--app-muted-strong);
}
.plan-card--offline .plan-icon {
  background: var(--app-surface-muted);
  color: var(--app-muted-strong);
}

/* ---------------- 头部 ---------------- */
.plan-card__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}
.plan-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}
.plan-icon {
  width: 36px;
  height: 36px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  color: var(--app-primary, var(--app-primary));
  background: var(--app-primary-soft, var(--app-primary-soft));
}
/* 档位图标底色（与色轨同构） */
.plan-icon--tier-free       { color: var(--app-muted-strong); background: var(--app-surface-muted); }
.plan-icon--tier-starter    { color: var(--ops-processing); background: var(--ops-processing-soft); }
.plan-icon--tier-advanced   { color: var(--app-primary); background: var(--app-primary-soft); }
.plan-icon--tier-enterprise { color: var(--app-accent); background: var(--app-accent-soft); }
.plan-icon--featured {
  color: #fff;
  background: var(--app-primary);
}
.plan-meta__text {
  min-width: 0;
}
.plan-name {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  line-height: 1.3;
  color: var(--app-fg, var(--app-fg));
}
.plan-desc {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--app-muted, var(--app-muted));
  /* 2 行省略, 对齐 */
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 36px;
}

/* 角标: 小巧 96px 以内, 不占视觉主位 */
.plan-badges {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4px;
  flex-shrink: 0;
}
.tag {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 3px 8px;
  height: 20px;
  box-sizing: border-box;
  border-radius: 4px;
  font-size: 11px;
  line-height: 1;
  font-weight: 500;
  white-space: nowrap;
}
.tag--featured {
  color: var(--app-primary);
  background: var(--app-primary-soft);
  border: 1px solid var(--app-primary-soft);
}
.tag--current {
  color: var(--app-success);
  background: var(--app-success-soft);
  border: 1px solid var(--app-success-soft);
}
.tag--queue {
  color: var(--app-warning);
  background: var(--app-warning-soft);
  border: 1px solid var(--app-warning-soft);
}
.tag--offline {
  color: var(--app-muted-strong);
  background: var(--app-surface-muted);
  border: 1px solid var(--app-border);
}
.tag > svg { flex-shrink: 0; }

/* ---------------- 分割线 ---------------- */
.divider {
  height: 1px;
  background: var(--app-border);
  margin: 0 -20px 16px;
}

/* ---------------- 价格: 左对齐, 字重克制 (600) ---------------- */
.plan-price {
  display: flex;
  align-items: baseline;
  gap: 2px;
  margin-bottom: 16px;
  line-height: 1;
}
.plan-price__currency {
  font-size: 14px;
  color: var(--app-fg, var(--app-fg));
  font-weight: 500;
}
.plan-price__amount {
  font-size: 26px;
  font-weight: 600;
  color: var(--app-fg, var(--app-fg));
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.2px;
}
.plan-price__amount--free {
  color: var(--app-fg, var(--app-fg));
}
.plan-card--featured .plan-price__amount {
  color: var(--app-primary, var(--app-primary));
}
.plan-price__period {
  font-size: 12px;
  color: var(--app-muted, var(--app-muted));
  margin-left: 4px;
}

/* ---------------- 额度 2 列: 等宽, 左对齐 ---------------- */
.plan-quota {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}
.quota-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  text-align: left;
  min-width: 0;
}
.quota-item__val {
  font-size: 15px;
  font-weight: 600;
  line-height: 1.2;
  color: var(--app-fg, var(--app-fg));
  font-variant-numeric: tabular-nums;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.quota-item__lbl {
  font-size: 11px;
  line-height: 1;
  color: var(--app-muted, var(--app-muted));
}

/* ---------------- 权益列表: 对勾小图标 + 等高 28px ---------------- */
.plan-features {
  list-style: none;
  margin: 0 0 12px;
  padding: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 0;
}
.feature-item {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 28px;            /* 等高行, 不参差 */
  font-size: 13px;
  line-height: 1;
  color: var(--app-fg, var(--app-fg));
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.feature-item__check {
  width: 14px;                 /* 小对勾, 非 20px 大绿圆 */
  height: 14px;
  flex-shrink: 0;
  color: var(--app-primary, var(--app-primary));
}
.plan-card--featured .feature-item__check {
  color: var(--app-primary, var(--app-primary));
}
.feature-item__text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ---------------- 可用模型: 细灰底小圆角, 高度统一 20px ---------------- */
.plan-models {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-bottom: 12px;
}
.model-chip {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 8px;
  border-radius: 4px;
  font-size: 11px;
  line-height: 1;
  color: var(--app-muted-strong);
  background: var(--app-surface-muted);
  white-space: nowrap;
}
.model-chip--more {
  color: var(--app-muted, var(--app-muted));
  background: transparent;
}

/* ---------------- 库存状态: 次要信息弱化 ---------------- */
.plan-stock {
  margin: 0 0 12px;
  font-size: 11px;
  line-height: 1.4;
  color: var(--app-muted, var(--app-muted));
}
.plan-stock--danger { color: var(--app-danger-text); }
.plan-stock--warn   { color: var(--app-warning-text); }

/* ---------------- 底部按钮: 统一 36px 高度, 贴底 ---------------- */
.plan-footer {
  margin-top: auto;
}
.plan-disabled {
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--app-muted, var(--app-muted));
  text-align: left;
}
.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  width: 100%;
  height: 36px;
  box-sizing: border-box;
  padding: 0 14px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 500;
  line-height: 1;
  font-family: inherit;
  cursor: pointer;
  border: 1px solid transparent;
  transition: background-color 160ms ease, border-color 160ms ease, color 160ms ease;
  white-space: nowrap;
}
.btn:focus-visible {
  outline: 2px solid #c6ddff;
  outline-offset: 1px;
}
.btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.btn--primary {
  color: #fff;
  background: var(--app-primary);
  border-color: var(--app-primary);
}
.btn--primary:hover:not(:disabled) {
  background: var(--app-primary-hover);
  border-color: var(--app-primary-hover);
}
.btn--outline {
  color: var(--app-primary);
  background: var(--app-surface);
  border-color: var(--app-primary-soft);
}
.btn--outline:hover:not(:disabled) {
  background: var(--app-primary-soft);
  border-color: var(--app-primary);
}
.btn--gray {
  color: var(--app-muted-strong);
  background: var(--app-surface-muted);
  border-color: var(--app-border);
  cursor: default;
}

/* ==========================================================================
   区块标题 + 套餐对比 + FAQ
   ========================================================================== */
.block-section {
  margin-top: 48px;
}
.section-title {
  margin-bottom: 16px;
}
.section-title h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  line-height: 1.4;
  color: var(--app-fg, var(--app-fg));
}
.section-title p {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--app-muted, var(--app-muted));
}

/* ---------- 对比表: B 端后台标准 ---------- */
.table-wrapper {
  overflow-x: auto;
  border: 1px solid var(--app-border);
  border-radius: 12px;
  background: var(--app-surface);
}
.compare-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.compare-table th,
.compare-table td {
  padding: 12px 16px;
  border-bottom: 1px solid var(--app-border);
  text-align: center;
  white-space: nowrap;
}
.compare-table thead th {
  background: var(--app-surface-subtle);
  color: var(--app-fg);
  font-weight: 600;
  text-align: center;
}
.compare-table td.col-func,
.compare-table th.col-func {
  text-align: left;
  color: var(--app-muted);
  font-weight: 500;
  background: var(--app-surface-subtle);
  position: sticky;
  left: 0;
  z-index: 1;
}
.compare-table tbody tr:last-child th,
.compare-table tbody tr:last-child td {
  border-bottom: 0;
}
.compare-table .col-featured {
  background: var(--app-primary-soft);
  color: var(--app-primary);
  font-weight: 600;
}

/* ---------- FAQ: 两列对称 grid, 紧凑 ---------- */
.faq-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
}
.faq-item {
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: 12px;
  overflow: hidden;
  transition: border-color 160ms ease;
}
.faq-item:hover { border-color: var(--app-border-strong); }
.faq-item[open] { border-color: var(--app-primary-soft); }

.faq-item__sum {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 18px;
  min-height: 48px;
  font-size: 13px;
  font-weight: 500;
  color: var(--app-fg, var(--app-fg));
  list-style: none;
  cursor: pointer;
  box-sizing: border-box;
}
.faq-item__sum::-webkit-details-marker { display: none; }
.faq-item__q {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.faq-item__arrow {
  flex-shrink: 0;
  color: var(--app-muted, var(--app-muted));
  transition: transform 160ms ease;
}
.faq-item[open] .faq-item__arrow { transform: rotate(180deg); }
.faq-item__a {
  margin: 0;
  padding: 0 18px 16px;
  font-size: 12px;
  line-height: 1.8;
  color: var(--app-muted, var(--app-muted));
}

/* ==========================================================================
   入场序列: 一次编排好的错峰上浮（降级方案见 tokens.css 全局 reduce）
   ========================================================================== */
.plans-grid,
.block-section {
  animation: plans-rise var(--app-motion-base) ease-out both;
}
.plans-grid { animation-delay: 120ms; }
.block-section:nth-of-type(1) { animation-delay: 200ms; }
.block-section:nth-of-type(2) { animation-delay: 260ms; }
@keyframes plans-rise {
  from { opacity: 0; transform: translateY(16px); }
  to   { opacity: 1; transform: none; }
}
@media (prefers-reduced-motion: reduce) {
  .plans-grid, .block-section { animation: none; }
}

/* ==========================================================================
   响应式
   ========================================================================== */
@media (max-width: 980px) {
  .page-container { padding: 0 20px; }
  .plans-grid { grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 16px; }
  .plan-card { min-height: 500px; padding: 18px; padding-left: 21px; }
  .divider { margin-left: -18px; margin-right: -18px; }
  .faq-grid { grid-template-columns: 1fr; }
}
@media (max-width: 640px) {
  .plans-page { padding: 20px 0 32px; }
  .page-container { padding: 0 16px; }
  .plans-grid { grid-template-columns: 1fr; }
  .plan-card { min-height: 0; }
  .block-section { margin-top: 32px; }
  .compare-table-wrapper { display: none; }
}
</style>
