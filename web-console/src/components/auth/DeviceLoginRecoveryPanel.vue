<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { isAxiosError } from 'axios'
import { completeDeviceLoginRecovery, type DeviceLoginRecovery, type SessionResponse } from '../../api/platform'

const props = defineProps<{ recovery: DeviceLoginRecovery }>()
const emit = defineEmits<{ completed: [session: SessionResponse]; cancel: [] }>()
const selected = ref<string[]>([])
const busy = ref(false)
const minimum = computed(() => Math.max(0, props.recovery.devices.length - props.recovery.deviceLimit + 1))
const formatTime = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })

async function submit() {
  if (busy.value || selected.value.length < minimum.value) return
  if (Date.now() >= Date.parse(props.recovery.expiresAt)) {
    ElMessage.warning('验证已过期，请重新登录')
    emit('cancel')
    return
  }
  busy.value = true
  try {
    const session = await completeDeviceLoginRecovery(props.recovery, selected.value)
    emit('completed', session)
  } catch (error) {
    const code = isAxiosError<{ code?: string }>(error) ? error.response?.data?.code : undefined
    if (code === 'login_device_recovery_expired' || code === 'login_device_selection_changed') {
      ElMessage.warning(code === 'login_device_selection_changed'
        ? '设备状态已变化，请重新登录后选择' : '验证已过期，请重新登录')
      emit('cancel')
    } else {
      ElMessage.error(code === 'login_device_limit_reached'
        ? '设备上限已变化，请返回重新登录并选择旧设备' : '设备退出失败，请稍后重试')
    }
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="device-recovery" aria-labelledby="login-title" data-test="device-recovery">
    <h2 id="login-title">登录设备已达上限</h2>
    <p>身份验证成功。当前上限为 {{ recovery.deviceLimit }} 台，请选择不再使用的旧设备，退出后登录。</p>
    <form @submit.prevent="submit">
      <fieldset :disabled="busy">
        <legend>至少选择 {{ minimum }} 台旧设备</legend>
        <label v-for="(device, index) in recovery.devices" :key="device.id" class="device-option">
          <input v-model="selected" type="checkbox" :value="device.id" :aria-label="`选择设备 ${index + 1}`" />
          <span class="device-details">
            <strong>设备 {{ index + 1 }} · {{ device.id.slice(-6) }}</strong>
            <span>登录时间：{{ formatTime(device.createdAt) }}</span>
          </span>
          <span class="presence" :class="{ online: device.recentlyOnline }">{{ device.recentlyOnline ? '近期在线' : '暂无心跳' }}</span>
        </label>
      </fieldset>
      <p class="device-help">暂无心跳也可能是休眠或断网，请只选择确认不用的设备。验证有效期为 5 分钟。</p>
      <div class="device-actions">
        <el-button :disabled="busy" @click="emit('cancel')">返回登录</el-button>
        <el-button type="primary" native-type="submit" :loading="busy" :disabled="selected.length < minimum" data-test="device-recovery-submit">退出所选并登录</el-button>
      </div>
    </form>
  </section>
</template>

<style scoped>
.device-recovery h2 { margin: 0 0 12px; font-size: 24px; }
.device-recovery p { color: var(--app-muted); line-height: 1.6; }
fieldset { margin: 20px 0 0; padding: 0; border: 0; min-width: 0; max-height: 320px; overflow-y: auto; }
legend { margin-bottom: 10px; font-weight: 600; }
.device-option { display: flex; align-items: center; gap: 10px; padding: 12px 0; border-bottom: 1px solid var(--app-border); cursor: pointer; }
.device-option input { flex-shrink: 0; width: 18px; height: 18px; accent-color: var(--app-primary, #2563eb); }
.device-details { display: grid; gap: 4px; min-width: 0; flex: 1; }
.device-details strong { font-size: 14px; }
.device-details > span, .presence, .device-help { font-size: 12px; }
.device-details > span { color: var(--app-muted); }
.presence { flex-shrink: 0; color: var(--app-muted); }
.presence.online { color: var(--app-primary, #2563eb); }
.device-actions { display: flex; flex-wrap: wrap; gap: 10px; justify-content: flex-end; margin-top: 20px; }
.device-actions :deep(.el-button) { margin: 0; min-height: 40px; }
</style>
