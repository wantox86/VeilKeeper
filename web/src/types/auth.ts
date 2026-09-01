import type { KdfParams } from '../crypto/kdfParams'

/**
 * Wire-format KDF params, matching the backend's `auth.KDFParams` Go struct
 * (`backend/internal/auth/kdf.go`) field-for-field -- note the field is
 * `memory`, not `memoryKiB` (the TS-side name used by `crypto/kdfParams.ts`,
 * mirroring Android's own internal naming). `toWireKdfParams`/
 * `fromWireKdfParams` below are the only place this renaming happens.
 */
export interface KdfParamsWire {
  memory: number
  iterations: number
  parallelism: number
}

export function toWireKdfParams(params: KdfParams): KdfParamsWire {
  return {
    memory: params.memoryKiB,
    iterations: params.iterations,
    parallelism: params.parallelism,
  }
}

export function fromWireKdfParams(wire: KdfParamsWire): KdfParams {
  return {
    memoryKiB: wire.memory,
    iterations: wire.iterations,
    parallelism: wire.parallelism,
  }
}

export interface PreloginResponse {
  kdf_salt: string
  kdf_params: KdfParamsWire
  kdf_version: number
}

export interface RegisterRequest {
  email: string
  username: string
  auth_key: string
  kdf_salt: string
  kdf_params: KdfParamsWire
  kdf_version: number
  wrapped_vdk: string
}

export interface RegisterResponse {
  user_id: number
  email: string
}

export interface LoginRequest {
  email: string
  auth_key: string
  device_identifier: string
  device_name: string
}

export interface LoginResponse {
  session_token: string
  expires_at: string
  wrapped_vdk: string
  kdf_salt: string
  kdf_params: KdfParamsWire
  kdf_version: number
}

export interface ApiErrorBody {
  error: string
  message: string
}
