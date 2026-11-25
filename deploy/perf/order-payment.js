import http from 'k6/http'
import { check, fail } from 'k6'
import exec from 'k6/execution'

export const options = {
  scenarios: {
    orders: {
      executor: 'constant-arrival-rate',
      rate: 30,
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 40,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.005'],
    http_req_duration: ['p(95)<500'],
  },
}

const BASE_URL = (__ENV.BASE_URL || 'http://localhost:18081').replace(/\/$/u, '')
const ACCESS_TOKEN = __ENV.ACCESS_TOKEN || ''
const PACKAGE_VERSION_ID = __ENV.PACKAGE_VERSION_ID || ''
const RUN_ID = __ENV.RUN_ID || ''
const PAYMENT_PROVIDER = (__ENV.PAYMENT_PROVIDER || 'MOCK').toUpperCase()

export function setup() {
  if (!ACCESS_TOKEN) fail('ACCESS_TOKEN is required')
  if (!PACKAGE_VERSION_ID) fail('PACKAGE_VERSION_ID is required')
  if (!/^[0-9a-f-]{36}$/iu.test(PACKAGE_VERSION_ID)) {
    fail('PACKAGE_VERSION_ID must be a UUID')
  }
  if (!/^[A-Za-z0-9._-]{1,64}$/u.test(RUN_ID)) {
    fail('RUN_ID must be 1-64 characters using letters, digits, dot, underscore, or hyphen')
  }
  if (PAYMENT_PROVIDER !== 'MOCK') fail('PAYMENT_PROVIDER must be MOCK')
}

export default function () {
  const idempotencyKey = `k6-${RUN_ID}-${exec.scenario.iterationInTest}`
  const response = http.post(
    `${BASE_URL}/console/platform/orders`,
    JSON.stringify({ packageVersionId: PACKAGE_VERSION_ID }),
    {
      headers: {
        Authorization: `Bearer ${ACCESS_TOKEN}`,
        'Content-Type': 'application/json',
        'Idempotency-Key': idempotencyKey,
      },
      tags: { operation: 'create-paid-order', provider: PAYMENT_PROVIDER },
    },
  )

  check(response, {
    'order created': (result) => result.status === 201,
  })
}
