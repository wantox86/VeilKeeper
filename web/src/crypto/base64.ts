/**
 * Standard base64 encode/decode for `Uint8Array`, used to move key material
 * (auth_key, wrapped_vdk, kdf_salt) over the JSON wire to/from the backend,
 * matching Android's `Base64.encode`/`decode` (`NO_WRAP` flavor) byte for
 * byte. Implemented over `atob`/`btoa` (universally available in browsers)
 * rather than a dependency -- these values are never large enough (a handful
 * of key-sized byte arrays) for the classic `atob`/`btoa` binary-string
 * inefficiency to matter here.
 */
export function bytesToBase64(bytes: Uint8Array): string {
  let binary = ''
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i])
  }
  return btoa(binary)
}

export function base64ToBytes(base64: string): Uint8Array {
  const binary = atob(base64)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i)
  }
  return bytes
}
