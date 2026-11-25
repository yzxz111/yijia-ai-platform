<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Camera, Check, KeyRound, LoaderCircle, Mail, MonitorSmartphone, Pencil, Phone, Send, ShieldCheck, Trash2, UserRound, X } from 'lucide-vue-next'
import { ElMessage } from 'element-plus'

import {
  bindEmail,
  bindPhone,
  changePassword,
  getProfile,
  requestEmailChallenge,
  requestPhoneChallenge,
  uploadAvatar,
  updateSmsPreference,
  updateDisplayName,
  updateLoginDeviceLimit,
  verifySmsChallenge,
  type PhoneSmsPurpose,
  type EmailChallenge,
  type ProfileView,
  type SmsChallenge,
} from '../../api/platform'
import { quotaSummary, type QuotaSummary } from '../../api/gateway'
import SmsCodeField from '../../components/sms/SmsCodeField.vue'
import SmsVerificationDialog from '../../components/sms/SmsVerificationDialog.vue'
import PasswordRecoveryForm from '../../components/auth/PasswordRecoveryForm.vue'
import { useAuthStore } from '../../stores/auth'
import { AvatarCompressionError, prepareAvatarForUpload } from '../../utils/avatarCompression'
import { syncProfileAvatar } from '../../utils/profileAvatar'
import { formatDateTime } from '../../utils/dateTime'
import { userPlanName } from '../../utils/planCopy'
import { statusLabel } from '../../utils/statusLabel'
import { avatarUploadErrorMessage } from './avatarUploadError'
import { profilePhoneBindErrorMessage, profilePhoneSendErrorMessage } from './profilePhoneError'

const router = useRouter()
const auth = useAuthStore()
const profile = ref<ProfileView | null>(null)
const quota = ref<QuotaSummary | null>(null)
const activeTab = ref<'basic' | 'security'>('basic')
const passwordMode = ref<'current' | 'recovery'>('current')
const currentPassword = ref('')
const newPassword = ref('')
const confirmation = ref('')
const passwordSubmitted = ref(false)
const passwordTouched = reactive({
  current: false,
  next: false,
  confirmation: false,
})
const displayName = ref('')
const nameEditing = ref(false)
const nameUpdating = ref(false)
const phone = ref('')
const phoneCode = ref('')
const phoneChallenge = ref<SmsChallenge | null>(null)
const phoneChangeVerificationOpen = ref(false)
const currentPhoneProof = ref('')
const email = ref('')
const emailCode = ref('')
const emailChallenge = ref<EmailChallenge | null>(null)
const emailChangeVerificationOpen = ref(false)
const currentEmailCode = ref('')
const currentEmailChallenge = ref<EmailChallenge | null>(null)
const currentEmailSending = ref(false)
const emailSending = ref(false)
const emailSubmitting = ref(false)
const passwordVerificationOpen = ref(false)
const submitting = ref(false)
const phoneSubmitting = ref(false)
const preferenceUpdating = ref(false)
const loginDeviceLimit = ref(5)
const deviceLimitUpdating = ref(false)
const avatarUploading = ref(false)
const errorMessage = ref('')
const avatarInput = ref<HTMLInputElement | null>(null)
let passwordVerificationSnapshot: {
  sessionEpoch: number
  userId: string
  tenantId: string
  profileId: string
  phoneState: ProfileView['phoneState']
} | null = null
function passwordComplexityError(value: string) {
  if (!value) return '请输入新密码。'
  if (value.length < 8) return '新密码至少需要 8 位。'
  if (value.length > 128) return '新密码不能超过 128 位。'
  if (new TextEncoder().encode(value).length > 72) return '新密码内容过长，请缩短后重试。'
  if (!/[a-z]/.test(value)) return '新密码需要包含小写字母。'
  if (!/[A-Z]/.test(value)) return '新密码需要包含大写字母。'
  if (!/\d/.test(value)) return '新密码需要包含数字。'
  if (!/[^A-Za-z0-9]/.test(value)) return '新密码需要包含特殊字符。'
  return ''
}

const strongPassword = computed(() => !passwordComplexityError(newPassword.value))
const passwordIsDifferent = computed(() => !profile.value?.passwordSet
  || !currentPassword.value
  || newPassword.value !== currentPassword.value)
const currentPasswordError = computed(() => {
  if (!passwordSubmitted.value && !passwordTouched.current) return ''
  if (profile.value?.passwordSet && !currentPassword.value) return '请输入当前密码。'
  return ''
})
const newPasswordError = computed(() => {
  if (!passwordSubmitted.value && !passwordTouched.next) return ''
  const value = newPassword.value
  const complexityError = passwordComplexityError(value)
  if (complexityError) return complexityError
  if (!passwordIsDifferent.value) return '新密码不能与当前密码相同。'
  return ''
})
const confirmationError = computed(() => {
  if (!passwordSubmitted.value && !passwordTouched.confirmation) return ''
  if (!confirmation.value) return '请再次输入新密码。'
  if (newPassword.value !== confirmation.value) return '两次输入的新密码不一致。'
  return ''
})
const canSubmit = computed(() => Boolean((!profile.value?.passwordSet || currentPassword.value)
  && strongPassword.value
  && passwordIsDifferent.value
  && newPassword.value === confirmation.value))
const phonePurpose = computed<PhoneSmsPurpose>(() => profile.value?.phoneVerified ? 'PHONE_CHANGE' : 'PHONE_BIND')
const canSavePhone = computed(() => Boolean(
  /^1[3-9]\d{9}$/.test(phone.value.trim())
  && phoneChallenge.value
  && /^\d{6}$/.test(phoneCode.value)
  && (!profile.value?.phoneVerified || currentPhoneProof.value)
  && !phoneSubmitting.value,
))
const canBindEmail = computed(() => Boolean(
  profile.value && /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email.value.trim())
    && emailChallenge.value && /^\d{6}$/.test(emailCode.value)
    && (!profile.value.email || (currentEmailChallenge.value && /^\d{6}$/.test(currentEmailCode.value)))
    && !emailSubmitting.value,
))
const emailChangeReady = computed(() => Boolean(
  !profile.value?.email
    || (currentEmailChallenge.value && /^\d{6}$/.test(currentEmailCode.value)),
))

function displayInitial(name: string): string {
  if (!name) return 'U'
  const trimmed = name.trim()
  // 取第一个汉字或第一个字母
  const match = trimmed.match(/^[\u4e00-\u9fa5]/)
  if (match) return match[0]
  return trimmed.charAt(0).toUpperCase()
}

async function loadProfile() {
  profile.value = await getProfile()
  if (!nameEditing.value) displayName.value = profile.value.displayName
  loginDeviceLimit.value = profile.value.maxLoginDevices
  await syncProfileAvatar(profile.value).catch(() => undefined)
}

async function loadQuota() {
  if (auth.isAdmin) {
    quota.value = null
    return
  }
  quota.value = await quotaSummary().catch(() => null)
}

const avatarSrc = computed(() => auth.avatarObjectUrl || '')
const activeDeviceCount = computed(() => profile.value?.activeDeviceCount ?? 0)
const canSaveDeviceLimit = computed(() => Boolean(
  profile.value
  && Number.isInteger(loginDeviceLimit.value)
  && loginDeviceLimit.value >= 1
  && loginDeviceLimit.value <= 10
  && loginDeviceLimit.value !== profile.value.maxLoginDevices
  && !deviceLimitUpdating.value,
))

function chooseAvatar() {
  if (avatarUploading.value) return
  avatarInput.value?.click()
}

function showAvatarError(message: string) {
  errorMessage.value = ''
  ElMessage.error(message)
}

async function handleAvatarSelected(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (!['image/jpeg', 'image/png', 'image/gif'].includes(file.type)) {
    showAvatarError('头像仅支持 JPG、PNG 或 GIF 图片。')
    return
  }
  if (avatarUploading.value) return
  avatarUploading.value = true
  errorMessage.value = ''
  try {
    const prepared = await prepareAvatarForUpload(file)
    await uploadAvatar(prepared.file)
    await loadProfile()
    ElMessage.success(prepared.compressed ? '头像已压缩并更新。' : '头像更新成功。')
  } catch (error) {
    showAvatarError(error instanceof AvatarCompressionError
      ? error.message
      : avatarUploadErrorMessage(error))
  } finally {
    avatarUploading.value = false
  }
}

function startNameEdit() {
  if (!profile.value) return
  displayName.value = profile.value.displayName
  nameEditing.value = true
}

function cancelNameEdit() {
  displayName.value = profile.value?.displayName || ''
  nameEditing.value = false
}

async function saveDisplayName() {
  const value = displayName.value.trim()
  if (!profile.value || !value || value.length > 25 || nameUpdating.value) return
  nameUpdating.value = true
  errorMessage.value = ''
  try {
    const result = await updateDisplayName(value)
    profile.value = { ...profile.value, displayName: result.displayName }
    auth.updateDisplayName(result.displayName)
    nameEditing.value = false
    ElMessage.success('用户名称更新成功。')
  } catch {
    errorMessage.value = '用户名称更新失败，请重试。'
  } finally {
    nameUpdating.value = false
  }
}

function sendPhoneChallenge() {
  if (profile.value?.phoneVerified && !currentPhoneProof.value) {
    return Promise.reject(new Error('current_phone_verification_required'))
  }
  return requestPhoneChallenge(phonePurpose.value, phone.value.trim())
}

function updatePhoneChallenge(challenge: SmsChallenge | null) {
  errorMessage.value = ''
  phoneChallenge.value = challenge
}

function completePhoneChangeVerification(proof: string) {
  currentPhoneProof.value = proof
  phoneChangeVerificationOpen.value = false
  ElMessage.success('当前手机号验证成功，请验证新手机号。')
}

async function sendCurrentEmailCode() {
  const currentEmail = profile.value?.email
  if (!currentEmail || currentEmailSending.value) return
  currentEmailSending.value = true
  errorMessage.value = ''
  try {
    currentEmailChallenge.value = await requestEmailChallenge(currentEmail)
    currentEmailCode.value = ''
  } catch {
    currentEmailChallenge.value = null
    errorMessage.value = '当前邮箱验证码发送失败，请稍后重试。'
  } finally {
    currentEmailSending.value = false
  }
}

function startEmailChangeVerification() {
  emailChangeVerificationOpen.value = true
  void sendCurrentEmailCode()
}

async function sendEmailCode() {
  if (emailSending.value || !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email.value.trim())) return
  if (profile.value?.email && (!currentEmailChallenge.value || !/^\d{6}$/.test(currentEmailCode.value))) {
    ElMessage.warning('请先获取并填写当前邮箱验证码。')
    return
  }
  emailSending.value = true
  errorMessage.value = ''
  try {
    emailChallenge.value = await requestEmailChallenge(email.value.trim())
    emailCode.value = ''
  } catch {
    emailChallenge.value = null
    errorMessage.value = '邮箱验证码暂未配置或发送失败，请稍后重试。'
  } finally {
    emailSending.value = false
  }
}

async function saveEmail() {
  const activeChallenge = emailChallenge.value
  if (!canBindEmail.value || !activeChallenge) return
  const replacingEmail = Boolean(profile.value?.email)
  emailSubmitting.value = true
  errorMessage.value = ''
  try {
    if (currentEmailChallenge.value) {
      await bindEmail(
        email.value.trim(),
        activeChallenge.id,
        emailCode.value,
        currentEmailChallenge.value.id,
        currentEmailCode.value,
      )
    } else {
      await bindEmail(email.value.trim(), activeChallenge.id, emailCode.value)
    }
    email.value = ''
    emailCode.value = ''
    emailChallenge.value = null
    currentEmailCode.value = ''
    currentEmailChallenge.value = null
    emailChangeVerificationOpen.value = false
    await loadProfile()
    ElMessage.success(replacingEmail ? '邮箱更新成功。' : '邮箱绑定成功。')
  } catch {
    emailCode.value = ''
    emailChallenge.value = null
    currentEmailCode.value = ''
    currentEmailChallenge.value = null
    errorMessage.value = '邮箱绑定失败，请确认验证码后重试。'
  } finally {
    emailSubmitting.value = false
  }
}

async function savePhone() {
  const activeChallenge = phoneChallenge.value
  const purpose = phonePurpose.value
  if (!canSavePhone.value || !activeChallenge) return
  phoneSubmitting.value = true
  errorMessage.value = ''
  try {
    await verifySmsChallenge(activeChallenge.id, phoneCode.value)
    if (currentPhoneProof.value) await bindPhone(activeChallenge.id, purpose, currentPhoneProof.value)
    else await bindPhone(activeChallenge.id, purpose)
    phone.value = ''
    phoneCode.value = ''
    phoneChallenge.value = null
    currentPhoneProof.value = ''
    await loadProfile()
    ElMessage.success(purpose === 'PHONE_CHANGE' ? '手机号更换成功。' : '手机号绑定成功。')
  } catch (error) {
    phoneCode.value = ''
    phoneChallenge.value = null
    errorMessage.value = profilePhoneBindErrorMessage(error)
  } finally {
    phoneSubmitting.value = false
  }
}

async function changeSmsPreference(enabled: boolean) {
  const current = profile.value
  if (!current || preferenceUpdating.value) return
  preferenceUpdating.value = true
  errorMessage.value = ''
  try {
    await updateSmsPreference(enabled)
    await loadProfile()
  } catch {
    profile.value = { ...current }
    errorMessage.value = '短信通知偏好更新失败，请重试。'
  } finally {
    preferenceUpdating.value = false
  }
}

async function performPasswordChange(smsProof?: string) {
  if (!canSubmit.value || submitting.value) return
  submitting.value = true
  errorMessage.value = ''
  try {
    if (smsProof) await changePassword(currentPassword.value, newPassword.value, smsProof)
    else await changePassword(currentPassword.value, newPassword.value)
    currentPassword.value = ''
    newPassword.value = ''
    confirmation.value = ''
    auth.clearSession()
    await router.replace('/login')
  } catch {
    errorMessage.value = '密码修改失败，请检查当前密码和新密码规则后重试。'
  } finally {
    submitting.value = false
  }
}

function submitPassword() {
  if (submitting.value) return
  passwordSubmitted.value = true
  passwordTouched.current = true
  passwordTouched.next = true
  passwordTouched.confirmation = true
  if (!canSubmit.value) {
    const invalidId = currentPasswordError.value
      ? 'current-password'
      : newPasswordError.value
        ? 'new-password'
        : 'confirm-new-password'
    document.getElementById(invalidId)?.focus()
    return
  }
  const currentProfile = profile.value
  const currentUser = auth.user
  if (currentProfile?.passwordSet && currentProfile.phoneVerified && currentUser) {
    passwordVerificationSnapshot = {
      sessionEpoch: auth.sessionEpoch,
      userId: currentUser.id,
      tenantId: currentUser.tenantId,
      profileId: currentProfile.id,
      phoneState: currentProfile.phoneState,
    }
    passwordVerificationOpen.value = true
  } else {
    void performPasswordChange()
  }
}

function completePasswordVerification(proof: string) {
  const snapshot = passwordVerificationSnapshot
  passwordVerificationSnapshot = null
  passwordVerificationOpen.value = false
  if (!snapshot
    || snapshot.sessionEpoch !== auth.sessionEpoch
    || snapshot.userId !== auth.user?.id
    || snapshot.tenantId !== auth.user?.tenantId
    || snapshot.profileId !== profile.value?.id
    || snapshot.phoneState !== profile.value?.phoneState) return
  void performPasswordChange(proof)
}

async function saveLoginDeviceLimit() {
  const current = profile.value
  if (!current || !canSaveDeviceLimit.value) return
  deviceLimitUpdating.value = true
  errorMessage.value = ''
  try {
    const result = await updateLoginDeviceLimit(loginDeviceLimit.value)
    profile.value = { ...current, maxLoginDevices: result.maxLoginDevices }
    loginDeviceLimit.value = result.maxLoginDevices
    ElMessage.success('登录设备上限已更新。')
  } catch {
    ElMessage.error('登录设备上限更新失败，请稍后重试。')
  } finally {
    deviceLimitUpdating.value = false
  }
}

function setPasswordMode(mode: 'current' | 'recovery') {
  passwordMode.value = mode
  currentPassword.value = ''
  newPassword.value = ''
  confirmation.value = ''
  passwordSubmitted.value = false
  passwordTouched.current = false
  passwordTouched.next = false
  passwordTouched.confirmation = false
}

async function completePasswordRecovery() {
  auth.clearSession()
  ElMessage.success('密码已重置，请使用新密码登录。')
  await router.replace('/login')
}

watch(() => auth.sessionEpoch, () => {
  passwordVerificationSnapshot = null
  passwordVerificationOpen.value = false
  phoneChangeVerificationOpen.value = false
  currentPhoneProof.value = ''
  emailChangeVerificationOpen.value = false
})

onBeforeUnmount(() => {
  passwordVerificationSnapshot = null
  passwordVerificationOpen.value = false
  phoneChangeVerificationOpen.value = false
  emailChangeVerificationOpen.value = false
})

onMounted(() => {
  void loadProfile().catch(() => { errorMessage.value = '个人资料加载失败，请稍后重试。' })
  if (!auth.isAdmin) void loadQuota()
})
</script>

<template>
  <SmsVerificationDialog
    v-model="passwordVerificationOpen"
    purpose="CHANGE_PASSWORD"
    @verified="completePasswordVerification"
  />
  <SmsVerificationDialog
    v-model="phoneChangeVerificationOpen"
    purpose="PHONE_CHANGE_CONFIRM"
    @verified="completePhoneChangeVerification"
  />
  <section class="profile-page" aria-labelledby="profile-title">
    <header class="page-header">
      <h2 id="profile-title">账号设置</h2>
      <p class="header-subtitle">管理您的账号信息与偏好设置</p>
    </header>
    <TopErrorMessage :message="errorMessage" />
    <el-skeleton v-if="!profile" :rows="8" animated />
    <template v-else>
      <nav class="profile-tabs" role="tablist" aria-label="个人中心设置">
        <button
          type="button"
          role="tab"
          data-test="profile-tab-basic"
          :aria-selected="activeTab === 'basic'"
          :class="{ active: activeTab === 'basic' }"
          @click="activeTab = 'basic'"
        >基本信息</button>
        <button
          type="button"
          role="tab"
          data-test="profile-tab-security"
          :aria-selected="activeTab === 'security'"
          :class="{ active: activeTab === 'security' }"
          @click="activeTab = 'security'"
        >安全设置</button>
      </nav>

      <!-- 基本信息 Tab -->
      <section v-if="activeTab === 'basic'" class="profile-tab-panel" role="tabpanel" aria-labelledby="profile-tab-basic">
        <!-- 头像卡片 -->
        <section class="card avatar-card" aria-labelledby="account-title">
          <input ref="avatarInput" class="avatar-input" type="file" accept="image/jpeg,image/png,image/gif" @change="handleAvatarSelected" />
          <div class="avatar-row">
            <el-tooltip :content="avatarUploading ? '正在处理头像' : '更换头像'" placement="top">
              <button
                type="button"
                class="profile-avatar"
                aria-label="更换头像"
                :aria-busy="avatarUploading"
                :disabled="avatarUploading"
                @click="chooseAvatar"
              >
                <img v-if="avatarSrc" :src="avatarSrc" alt="用户头像" />
                <span v-else class="avatar-initial">{{ displayInitial(profile.displayName) }}</span>
                <span class="avatar-camera" aria-hidden="true">
                  <LoaderCircle v-if="avatarUploading" class="avatar-spinner" :size="14" />
                  <Camera v-else :size="14" />
                </span>
              </button>
            </el-tooltip>
            <div class="avatar-info">
              <div v-if="nameEditing" class="name-editor">
                <el-input
                  v-model="displayName"
                  data-test="display-name-input"
                  maxlength="25"
                  aria-label="用户名称"
                  @keyup.enter="saveDisplayName"
                  @keyup.esc="cancelNameEdit"
                />
                <el-tooltip content="保存名称" placement="top">
                  <button
                    type="button"
                    class="name-action"
                    data-test="save-display-name"
                    aria-label="保存名称"
                    :disabled="!displayName.trim() || nameUpdating"
                    @click="saveDisplayName"
                  ><Check :size="16" aria-hidden="true" /></button>
                </el-tooltip>
                <el-tooltip content="取消编辑" placement="top">
                  <button type="button" class="name-action" aria-label="取消编辑" @click="cancelNameEdit"><X :size="16" aria-hidden="true" /></button>
                </el-tooltip>
              </div>
              <div v-else class="name-display">
                <h3 id="account-title">{{ profile.displayName }}</h3>
                <el-tooltip content="修改用户名称" placement="top">
                  <button
                    type="button"
                    class="name-action"
                    data-test="edit-display-name"
                    aria-label="修改用户名称"
                    @click="startNameEdit"
                  ><Pencil :size="15" aria-hidden="true" /></button>
                </el-tooltip>
              </div>
              <p class="avatar-meta">
                {{ profile.email || '未绑定邮箱' }}
                <template v-if="!auth.isAdmin && quota?.activePackage" class="plan-badge">
                  {{ userPlanName(quota.activePackage.packageCode, quota.activePackage.packageName) }}
                </template>
                <template v-else-if="profile.userNo" class="plan-badge subtle">
                  用户编号 {{ profile.userNo }}
                </template>
              </p>
              <div class="avatar-actions">
                <button type="button" class="btn btn-secondary" :disabled="avatarUploading" @click="chooseAvatar">
                  <Camera :size="14" /> 上传头像
                </button>
              </div>
            </div>
            <!-- 右侧信息栏 -->
            <div class="avatar-sidebar">
              <div class="sidebar-meta">
                <div class="sidebar-meta-item">
                  <span class="sidebar-meta-label">账户状态</span>
                  <span class="sidebar-meta-value">{{ statusLabel(profile.status) }}</span>
                </div>
                <div class="sidebar-meta-item">
                  <span class="sidebar-meta-label">最近登录</span>
                  <span class="sidebar-meta-value">{{ profile.lastLoginAt ? formatDateTime(profile.lastLoginAt) : '首次登录' }}</span>
                </div>
                <div class="sidebar-meta-item">
                  <span class="sidebar-meta-label">活跃会话</span>
                  <span class="sidebar-meta-value">{{ activeDeviceCount }} / {{ profile.maxLoginDevices }}</span>
                </div>
              </div>
            </div>
          </div>
        </section>

        <!-- 账户摘要卡 -->
        <section class="card info-summary" aria-label="账户摘要">
          <div class="info-summary-grid">
            <div class="info-summary-item">
              <span class="info-summary-icon user"><UserRound :size="16" /></span>
              <div class="info-summary-body">
                <div class="info-summary-label">用户编号</div>
                <div class="info-summary-value mono">{{ profile.userNo || '--' }}</div>
              </div>
            </div>
            <div class="info-summary-item">
              <span class="info-summary-icon phone"><Phone :size="16" /></span>
              <div class="info-summary-body">
                <div class="info-summary-label">手机号</div>
                <div class="info-summary-value" :class="{ muted: !profile.phoneVerified }">
                  {{ profile.maskedPhone || '未绑定' }}
                </div>
              </div>
              <el-tag v-if="!profile.phoneVerified" size="small" type="warning" effect="light">未验证</el-tag>
            </div>
            <div class="info-summary-item">
              <span class="info-summary-icon email"><Mail :size="16" /></span>
              <div class="info-summary-body">
                <div class="info-summary-label">邮箱</div>
                <div class="info-summary-value" :class="{ muted: !profile.email }">
                  {{ profile.email || '未绑定' }}
                </div>
              </div>
              <el-tag v-if="!profile.email" size="small" type="warning" effect="light">未绑定</el-tag>
            </div>
            <div v-if="!auth.isAdmin" class="info-summary-item">
              <span class="info-summary-icon plan"><KeyRound :size="16" /></span>
              <div class="info-summary-body">
                <div class="info-summary-label">当前套餐</div>
                <div class="info-summary-value">
                  {{ quota?.activePackage?.packageName || quota?.activePackage?.packageCode || '免费版' }}
                </div>
              </div>
              <el-button v-if="quota?.activePackage" type="primary" link size="small" @click="$router.push('/app/plans')">管理</el-button>
            </div>
          </div>
        </section>

        <!-- 个人信息表单卡片 -->
        <section class="card" aria-labelledby="personal-info-title">
          <h3 id="personal-info-title" class="card-title">个人信息</h3>
          <div class="form-grid">
            <div class="form-item">
              <label>邮箱地址</label>
              <div v-if="!profile.email" class="email-binding-inline">
                <el-input v-model="email" type="email" placeholder="请输入邮箱地址" data-test="profile-email" />
                <div class="email-inline-actions">
                  <el-button data-test="profile-email-send" type="primary" :loading="emailSending" :disabled="!email || emailSending" @click="sendEmailCode">
                    <Send v-if="!emailSending" :size="15" /> 发送验证码
                  </el-button>
                </div>
                <el-input data-test="profile-email-code" v-model="emailCode" inputmode="numeric" maxlength="6" placeholder="6 位验证码" aria-label="邮箱验证码" />
                <el-button type="primary" data-test="save-email" :disabled="!canBindEmail" :loading="emailSubmitting" @click="saveEmail">绑定邮箱</el-button>
              </div>
              <div v-else class="form-readonly">
                <span class="with-icon">
                  <ShieldCheck :size="15" class="inline-icon" /> {{ profile.email }}
                </span>
                <button type="button" class="link-edit" @click="startEmailChangeVerification">更换</button>
              </div>
              <!-- 邮箱更换流程内联 -->
              <template v-if="profile.email && emailChangeVerificationOpen">
                <div class="inline-verification">
                  <template v-if="!emailChangeReady">
                    <div class="inline-code-row">
                      <el-input v-model="currentEmailCode" inputmode="numeric" maxlength="6" placeholder="当前邮箱验证码" aria-label="当前邮箱验证码" />
                      <el-button type="primary" plain :loading="currentEmailSending" @click="sendCurrentEmailCode">
                        <Send v-if="!currentEmailSending" :size="15" /> 发送
                      </el-button>
                    </div>
                  </template>
                  <template v-else>
                    <el-input v-model="email" type="email" placeholder="新邮箱地址" />
                    <div class="inline-code-row">
                      <el-input data-test="profile-email-code" v-model="emailCode" inputmode="numeric" maxlength="6" placeholder="新邮箱验证码" aria-label="新邮箱验证码" />
                      <el-button data-test="profile-email-send" type="primary" :loading="emailSending" :disabled="!email || emailSending" @click="sendEmailCode">
                        <Send v-if="!emailSending" :size="15" /> 发送
                      </el-button>
                    </div>
                    <div class="inline-actions">
                      <el-button type="primary" :disabled="!canBindEmail" :loading="emailSubmitting" @click="saveEmail">确认更换</el-button>
                      <el-button @click="emailChangeVerificationOpen = false">取消</el-button>
                    </div>
                  </template>
                </div>
              </template>
            </div>

            <div class="form-item">
              <label>手机号</label>
              <div v-if="!profile.phoneVerified" class="phone-binding-inline">
                <el-input
                  v-model="phone"
                  data-test="profile-phone"
                  type="tel"
                  name="tel"
                  inputmode="tel"
                  autocomplete="tel"
                  maxlength="11"
                  placeholder="请输入手机号"
                />
                <SmsCodeField
                  v-model="phoneCode"
                  :phone="phone"
                  require-phone
                  :disabled="phoneSubmitting"
                  :send-challenge="sendPhoneChallenge"
                  :format-send-error="profilePhoneSendErrorMessage"
                  @challenge="updatePhoneChallenge"
                />
                <el-button
                  type="primary"
                  data-test="save-phone"
                  :disabled="!canSavePhone"
                  :loading="phoneSubmitting"
                  @click="savePhone"
                >绑定手机号</el-button>
              </div>
              <div v-else class="form-readonly">
                <span class="with-icon">
                  <Phone :size="15" class="inline-icon" /> {{ profile.maskedPhone || '已验证' }}
                </span>
                <button type="button" class="link-edit" @click="phoneChangeVerificationOpen = true">更换</button>
              </div>
            </div>

            <div class="form-item">
              <label>短信通知</label>
              <div class="form-switch-row">
                <span class="switch-desc">{{ profile.smsNotificationsEnabled ? '已开启业务短信通知' : '已关闭业务短信通知' }}</span>
                <el-switch
                  data-test="sms-preference"
                  aria-label="业务短信通知"
                  :model-value="profile.smsNotificationsEnabled"
                  :loading="preferenceUpdating"
                  @change="changeSmsPreference"
                />
              </div>
            </div>
          </div>
        </section>

        <!-- 当前订阅套餐 -->
        <section v-if="!auth.isAdmin && quota?.activePackage" class="card" aria-labelledby="plan-title">
          <h3 id="plan-title" class="card-title">当前订阅套餐</h3>
          <div class="plan-box">
            <div class="plan-info">
              <div class="plan-name">{{ userPlanName(quota.activePackage.packageCode, quota.activePackage.packageName) }}</div>
              <div class="plan-meta">
                <template v-if="quota.activePackage.expiresAt">
                  到期时间：{{ formatDateTime(quota.activePackage.expiresAt) }}
                </template>
                <template v-else>
                  永久套餐
                </template>
                <span v-if="quota.activePackage.remainingTokens != null" class="plan-sep">·</span>
                <template v-if="quota.activePackage.remainingTokens != null">
                  Token 剩余 {{ quota.activePackage.remainingTokens.toLocaleString() }}
                </template>
              </div>
            </div>
            <div class="plan-actions">
              <el-button type="primary" plain @click="$router.push('/app/plans')">管理套餐</el-button>
            </div>
          </div>
        </section>

        <div class="form-footer">
          <el-button type="primary" size="large" @click="$router.push('/profile')">保存修改</el-button>
        </div>
      </section>

      <!-- 安全设置 Tab -->
      <section v-else class="profile-tab-panel" role="tabpanel" aria-labelledby="profile-tab-security">
        <!-- 修改密码 -->
        <section class="card" aria-labelledby="password-title">
          <h3 id="password-title" class="card-title">修改密码</h3>
          <div v-if="profile.passwordSet" class="password-mode-switch" role="tablist" aria-label="密码修改方式">
            <button
              id="password-mode-current"
              type="button"
              role="tab"
              data-test="password-mode-current"
              :aria-selected="passwordMode === 'current'"
              aria-controls="current-password-panel"
              :class="{ active: passwordMode === 'current' }"
              @click="setPasswordMode('current')"
            >使用当前密码</button>
            <button
              id="password-mode-recovery"
              type="button"
              role="tab"
              data-test="password-mode-recovery"
              :aria-selected="passwordMode === 'recovery'"
              aria-controls="recovery-password-panel"
              :class="{ active: passwordMode === 'recovery' }"
              @click="setPasswordMode('recovery')"
            >通过验证码重置</button>
          </div>
          <form
            v-if="passwordMode === 'current'"
            id="current-password-panel"
            class="password-form"
            data-test="password-form"
            novalidate
            :role="profile.passwordSet ? 'tabpanel' : undefined"
            :aria-labelledby="profile.passwordSet ? 'password-mode-current' : undefined"
            @submit.prevent="submitPassword"
          >
            <div class="form-grid">
              <div class="form-item">
                <label for="current-password">{{ profile.passwordSet ? '当前密码' : '当前密码（首次设置可留空）' }}</label>
                <el-input
                  id="current-password"
                  v-model="currentPassword"
                  data-test="current-password"
                  type="password"
                  name="current-password"
                  autocomplete="current-password"
                  show-password
                  :aria-required="profile.passwordSet"
                  :aria-invalid="Boolean(currentPasswordError)"
                  :aria-describedby="currentPasswordError ? 'current-password-error' : undefined"
                  :class="{ 'is-invalid': currentPasswordError }"
                  @blur="passwordTouched.current = true"
                />
                <p v-if="currentPasswordError" id="current-password-error" class="field-error" data-test="current-password-error" role="alert">{{ currentPasswordError }}</p>
              </div>
              <div class="form-item">
                <label for="new-password">新密码</label>
                <el-input
                  id="new-password"
                  v-model="newPassword"
                  data-test="new-password"
                  type="password"
                  name="new-password"
                  autocomplete="new-password"
                  show-password
                  aria-required="true"
                  :aria-invalid="Boolean(newPasswordError)"
                  :aria-describedby="newPasswordError ? 'new-password-help new-password-error' : 'new-password-help'"
                  :class="{ 'is-invalid': newPasswordError }"
                  @blur="passwordTouched.next = true"
                />
                <p id="new-password-help" class="field-help">至少 8 位，包含大小写字母、数字和特殊字符。</p>
                <p v-if="newPasswordError" id="new-password-error" class="field-error" data-test="new-password-error" role="alert">{{ newPasswordError }}</p>
              </div>
              <div class="form-item">
                <label for="confirm-new-password">确认新密码</label>
                <el-input
                  id="confirm-new-password"
                  v-model="confirmation"
                  data-test="confirm-new-password"
                  type="password"
                  name="confirm-new-password"
                  autocomplete="new-password"
                  show-password
                  aria-required="true"
                  :aria-invalid="Boolean(confirmationError)"
                  :aria-describedby="confirmationError ? 'confirm-new-password-error' : undefined"
                  :class="{ 'is-invalid': confirmationError }"
                  @blur="passwordTouched.confirmation = true"
                />
                <p v-if="confirmationError" id="confirm-new-password-error" class="field-error" data-test="confirm-new-password-error" role="alert">{{ confirmationError }}</p>
              </div>
            </div>
            <p class="form-note">修改成功后需要重新登录。</p>
            <el-button data-test="update-password" native-type="submit" type="primary" size="large" :disabled="submitting" :loading="submitting">更新密码</el-button>
          </form>
          <div
            v-else
            id="recovery-password-panel"
            class="password-recovery-content"
            data-test="password-recovery-panel"
            role="tabpanel"
            aria-labelledby="password-mode-recovery"
          >
            <p class="form-note">重置成功后，当前登录会话将失效，需要使用新密码重新登录。</p>
            <PasswordRecoveryForm @completed="completePasswordRecovery" />
          </div>
        </section>

        <!-- 登录设备 -->
        <section class="card" aria-labelledby="device-policy-title">
          <h3 id="device-policy-title" class="card-title">登录设备</h3>
          <div class="device-section">
            <div class="device-limit-summary" data-test="device-limit-summary">
              <span>当前活跃设备</span>
              <strong>{{ activeDeviceCount }} / {{ profile.maxLoginDevices }}</strong>
            </div>
            <div class="device-limit-setting">
              <div class="device-limit-copy">
                <label for="login-device-limit">最大登录设备数</label>
                <p>可设置 1–10 台；调低上限不会退出当前设备。</p>
              </div>
              <div class="device-limit-actions">
                <el-input-number
                  id="login-device-limit"
                  v-model="loginDeviceLimit"
                  data-test="device-limit-input"
                  :min="1"
                  :max="10"
                  :step="1"
                  :precision="0"
                  :disabled="deviceLimitUpdating"
                  controls-position="right"
                  aria-describedby="device-limit-help"
                />
                <el-button
                  type="primary"
                  data-test="save-device-limit"
                  :disabled="!canSaveDeviceLimit"
                  :loading="deviceLimitUpdating"
                  @click="saveLoginDeviceLimit"
                >保存</el-button>
              </div>
            </div>
            <p id="device-limit-help" class="field-help">达到上限后，新设备将无法登录。</p>
          </div>
        </section>
      </section>
    </template>
  </section>
</template>

<style scoped>
.profile-page { width: min(100%, 1045px); min-width: 0; margin: 0 auto; }

/* 页头 */
.page-header { margin-bottom: 16px; }
.page-header h2 { margin: 0; font-size: 22px; font-weight: 700; color: var(--app-fg); }
.header-subtitle { margin: 4px 0 0; color: var(--app-muted); font-size: 13px; }

/* Tabs */
.profile-tabs {
  display: flex;
  gap: 0;
  margin-bottom: 14px;
  border-bottom: 1px solid var(--app-border);
}
.profile-tabs button {
  min-height: 40px;
  padding: 0 18px;
  color: var(--app-muted);
  background: transparent;
  border: 0;
  border-bottom: 2px solid transparent;
  font: inherit;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: color 0.18s ease, border-color 0.18s ease;
  margin-bottom: -1px;
}
.profile-tabs button:hover { color: var(--app-primary); }
.profile-tabs button.active {
  color: var(--app-primary);
  border-bottom-color: var(--app-primary);
  font-weight: 600;
}

.profile-tab-panel { min-width: 0; display: flex; flex-direction: column; gap: 12px; }

/* Card */
.card {
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: 12px;
  padding: 18px 22px;
}
.card-title {
  margin: 0 0 14px;
  font-size: 15px;
  font-weight: 600;
  color: var(--app-fg);
}

/* 头像卡片 */
.avatar-card { padding: 20px 22px; }
.avatar-input { position: absolute; width: 1px; height: 1px; overflow: hidden; opacity: 0; pointer-events: none; }
.avatar-row { display: grid; grid-template-columns: auto 1fr auto; gap: 24px; align-items: start; min-width: 0; }
.avatar-actions { display: flex; gap: 8px; margin-top: 10px; }
.profile-avatar {
  position: relative;
  display: inline-flex;
  width: 72px;
  height: 72px;
  padding: 0;
  align-items: center;
  justify-content: center;
  color: #fff;
  background: var(--app-primary);
  border: 0;
  border-radius: 50%;
  cursor: pointer;
  overflow: hidden;
  box-shadow: 0 2px 12px rgba(59, 130, 246, 0.25);
  font-size: 28px;
  font-weight: 700;
  transition: transform 0.18s ease, box-shadow 0.18s ease;
}
.profile-avatar:hover { transform: scale(1.03); box-shadow: 0 4px 16px rgba(59, 130, 246, 0.32); }
.profile-avatar img { width: 100%; height: 100%; object-fit: cover; border-radius: 50%; }
.avatar-initial { font-size: 28px; font-weight: 700; line-height: 1; }
.profile-avatar:focus-visible { outline: 2px solid var(--app-primary); outline-offset: 2px; }
.profile-avatar:disabled { cursor: wait; opacity: 0.72; }
.avatar-camera {
  position: absolute;
  right: -2px;
  bottom: -2px;
  display: inline-flex;
  width: 22px;
  height: 22px;
  align-items: center;
  justify-content: center;
  color: #fff;
  background: var(--app-fg);
  border: 2px solid #fff;
  border-radius: 50%;
}
.avatar-spinner { animation: avatar-spin 0.8s linear infinite; }
@keyframes avatar-spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .avatar-spinner { animation: none; } }

.btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 0 12px;
  height: 32px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.18s ease;
  border: 1px solid transparent;
}
.btn-secondary {
  color: var(--app-muted-strong);
  background: var(--app-surface);
  border-color: var(--app-border);
}
.btn-secondary:hover { color: var(--app-primary); border-color: var(--app-primary); background: var(--app-primary-soft); }
.btn:disabled { cursor: not-allowed; opacity: 0.5; }

.avatar-info { min-width: 0; flex: 1; }
.name-display, .name-editor { display: flex; min-width: 0; align-items: center; gap: 8px; }
.name-display h3 { margin: 0; font-size: 18px; font-weight: 700; color: var(--app-fg); overflow-wrap: anywhere; }
.name-editor { max-width: 320px; }
.name-editor .el-input { min-width: 0; }
.avatar-meta {
  margin: 5px 0 0;
  color: var(--app-muted);
  font-size: 12px;
  overflow-wrap: anywhere;
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.plan-badge {
  display: inline-flex;
  align-items: center;
  padding: 2px 10px;
  border-radius: 20px;
  background: var(--app-primary-soft);
  color: var(--app-primary);
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}
.plan-badge.subtle {
  background: var(--app-surface-muted);
  color: var(--app-muted);
  font-weight: 500;
}

/* 头像侧栏 */
.avatar-sidebar {
  width: 280px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.sidebar-plan {
  padding: 12px 14px;
  background: var(--app-primary-soft);
  border: 1px solid var(--app-primary-border);
  border-radius: 8px;
}
.sidebar-plan-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
}
.sidebar-plan-name { font-size: 13px; font-weight: 600; color: var(--app-fg); }
.sidebar-plan-label { font-size: 11px; color: var(--app-muted); margin-top: 6px; }
.sidebar-plan-count { font-size: 14px; font-weight: 600; color: var(--app-primary); margin-top: 2px; }
.sidebar-plan-expire { font-size: 11px; color: var(--app-muted); margin-top: 6px; }
.sidebar-meta {
  display: flex;
  flex-direction: column;
  gap: 0;
  background: var(--app-surface-subtle);
  border: 1px solid var(--app-border);
  border-radius: 8px;
  overflow: hidden;
}
.sidebar-meta-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 14px;
  font-size: 12px;
  border-bottom: 1px solid var(--app-border);
}
.sidebar-meta-item:last-child { border-bottom: 0; }
.sidebar-meta-label { color: var(--app-muted); }
.sidebar-meta-value { color: var(--app-fg); font-weight: 500; }

/* 信息汇总卡片 */
.info-summary { padding: 16px 22px; }
.info-summary-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 16px;
}
.info-summary-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  background: var(--app-surface-subtle);
  border: 1px solid var(--app-border);
  border-radius: 8px;
  min-width: 0;
  transition: border-color 0.18s ease, background 0.18s ease;
}
.info-summary-item:hover {
  border-color: var(--app-primary);
  background: var(--app-primary-soft);
}
.info-summary-icon {
  display: inline-flex;
  flex: 0 0 32px;
  width: 32px;
  height: 32px;
  align-items: center;
  justify-content: center;
  border-radius: 7px;
  color: #fff;
}
.info-summary-icon.user  { background: var(--app-primary); }
.info-summary-icon.phone { background: var(--app-success); }
.info-summary-icon.email { background: var(--app-accent); }
.info-summary-icon.plan  { background: var(--app-primary); }
.info-summary-body { min-width: 0; flex: 1; }
.info-summary-label {
  font-size: 11px;
  color: var(--app-muted);
  line-height: 1.2;
  margin-bottom: 3px;
}
.info-summary-value {
  font-size: 13px;
  font-weight: 600;
  color: var(--app-fg);
  /* 邮箱等长值允许折行：此前 nowrap + ellipsis 会把用户自己的邮箱截成
     "skilltest-1789184820@local.t…"（实测内容 182px / 可视 164px），信息直接丢失。 */
  overflow-wrap: anywhere;
}
.info-summary-value.mono { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 12px; }
.info-summary-value.muted { color: var(--app-muted); font-weight: 500; }

.name-action {
  display: inline-flex;
  width: 28px;
  height: 28px;
  flex: 0 0 28px;
  align-items: center;
  justify-content: center;
  color: var(--app-muted);
  background: transparent;
  border: 1px solid transparent;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.18s ease;
}
.name-action:hover { color: var(--app-primary); background: var(--app-primary-soft); }
.name-action:focus-visible { outline: 2px solid var(--app-primary); outline-offset: 2px; }
.name-action:disabled { cursor: not-allowed; opacity: 0.45; }

/* 表单栅格 */
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px 18px;
  align-items: start;
}
.form-item { display: flex; flex-direction: column; gap: 5px; min-width: 0; }
.form-item-full { grid-column: 1 / -1; }
.form-item label {
  font-size: 12px;
  font-weight: 600;
  color: var(--app-muted-strong);
}
.form-readonly {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 34px;
  padding: 6px 10px;
  background: var(--app-surface-subtle);
  border: 1px solid var(--app-border);
  border-radius: 6px;
  color: var(--app-fg);
  font-size: 13px;
}
.with-icon { display: inline-flex; align-items: center; gap: 6px; }
.inline-icon { color: var(--app-primary); flex-shrink: 0; }
.link-edit {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 2px 8px;
  color: var(--app-primary);
  background: transparent;
  border: 0;
  border-radius: 5px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.18s ease;
}
.link-edit:hover { background: var(--app-primary-soft); }

/* 邮箱／手机号行内绑定 */
.email-binding-inline,
.phone-binding-inline {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.email-inline-actions { display: flex; justify-content: flex-end; }
.inline-verification {
  margin-top: 8px;
  padding: 10px 12px;
  background: var(--app-surface-subtle);
  border: 1px solid var(--app-border);
  border-radius: 6px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.inline-code-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
  align-items: center;
}
.inline-actions { display: flex; gap: 8px; }

/* 开关行 */
.form-switch-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 34px;
  padding: 6px 10px;
  background: var(--app-surface-subtle);
  border: 1px solid var(--app-border);
  border-radius: 6px;
}
.switch-desc { color: var(--app-muted-strong); font-size: 13px; }

/* 字段帮助／错误提示 */
.field-help { margin: 0; font-size: 12px; color: var(--app-muted); line-height: 1.5; }
.field-error { margin: 0; font-size: 12px; color: var(--app-danger); line-height: 1.5; }
.form-note { margin: 0; font-size: 13px; color: var(--app-muted); }

/* 密码表单 */
.password-mode-switch {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 4px;
  margin-bottom: 14px;
  padding: 4px;
  background: var(--app-surface-subtle);
  border: 1px solid var(--app-border);
  border-radius: 7px;
}
.password-mode-switch button {
  min-height: 34px;
  padding: 0 12px;
  color: var(--app-muted);
  background: transparent;
  border: 0;
  border-radius: 5px;
  font: inherit;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.18s ease;
}
.password-mode-switch button:hover { color: var(--app-primary); }
.password-mode-switch button.active {
  color: var(--app-primary);
  background: var(--app-surface);
  box-shadow: 0 0 0 1px var(--app-border);
  font-weight: 600;
}
.password-mode-switch button:focus-visible { outline: 2px solid var(--app-primary); outline-offset: 2px; }
.password-form { display: flex; flex-direction: column; gap: 12px; }
.password-form .form-grid { grid-template-columns: minmax(0, 1fr); margin-bottom: 2px; }
.password-form .is-invalid :deep(.el-input__wrapper) { border-color: var(--app-danger); box-shadow: 0 0 0 1px var(--app-danger) inset; }
.password-recovery-content { display: flex; flex-direction: column; gap: 10px; }

/* 套餐信息框 */
.plan-box {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  background: var(--app-primary-soft);
  border: 1px solid var(--app-primary-border);
  border-radius: 8px;
}
.plan-info { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.plan-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--app-fg);
}
.plan-meta {
  font-size: 13px;
  color: var(--app-primary);
}
.plan-sep { margin: 0 6px; color: var(--app-muted); }
.plan-actions { display: flex; gap: 8px; flex-shrink: 0; }

/* 设备区块 */
.device-section { display: flex; flex-direction: column; gap: 10px; }
.device-limit-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  background: var(--app-surface-subtle);
  border: 1px solid var(--app-border);
  border-radius: 6px;
}
.device-limit-summary span { color: var(--app-muted-strong); font-size: 13px; }
.device-limit-summary strong { color: var(--app-primary); font-size: 16px; font-variant-numeric: tabular-nums; font-weight: 700; }
.device-limit-setting {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 0;
  border-top: 1px solid var(--app-border);
  border-bottom: 1px solid var(--app-border);
}
.device-limit-copy { display: grid; gap: 2px; min-width: 0; }
.device-limit-copy label { font-weight: 600; color: var(--app-fg); font-size: 13px; }
.device-limit-copy p { margin: 0; font-size: 12px; color: var(--app-muted); line-height: 1.5; }
.device-limit-actions { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.device-limit-actions :deep(.el-input-number) { width: 120px; }

/* 页脚 */
.form-footer { display: flex; justify-content: flex-start; padding-top: 2px; }

/* Element Plus 覆盖样式 */
:deep(.el-input__wrapper) { min-height: 36px; border-radius: 6px; }
:deep(.el-button--large) { min-height: 38px; padding: 0 18px; }
:deep(.el-button--primary) { border-radius: 6px; }
:deep(.el-button) { border-radius: 6px; }

@media (max-width: 860px) {
  .form-grid { grid-template-columns: minmax(0, 1fr); }
  .avatar-row { grid-template-columns: 1fr; gap: 16px; }
  .avatar-sidebar { width: 100%; }
  .info-summary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
  .inline-code-row { grid-template-columns: minmax(0, 1fr); }
  .device-limit-setting { flex-direction: column; align-items: stretch; }
  .device-limit-actions :deep(.el-input-number) { width: 100%; }
}
@media (max-width: 520px) {
  .card { padding: 16px 14px; }
  .plan-box { flex-direction: column; align-items: flex-start; gap: 10px; }
}
</style>
