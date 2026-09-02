/**
 * Compresses a picked image (SPEC-BASE.md Section 17's "Compress if
 * appropriate" step) BEFORE it reaches `crypto/attachmentCrypto.ts` --
 * encrypting first and compressing after would be pointless (ciphertext
 * doesn't compress) and would upload/store far more bytes than needed for a
 * vault whose images are mostly screenshots (SPEC-BASE.md Section 17's own
 * examples: "Screenshot VPN", "Screenshot API configuration").
 *
 * Mirrors Android's `ImageCompressor.kt`
 * (`android/app/src/main/java/id/quezacolt/veilkeeper/data/ImageCompressor.kt`)
 * field-for-field: downscale to at most `MAX_DIMENSION_PX` on the longest
 * side, re-encode as JPEG at `JPEG_QUALITY`. A plain, unconfigurable policy,
 * deliberately -- this is a vault for reference screenshots, not a photo
 * editor, so exposing quality/format knobs would be scope creep (Section 56
 * Rule 1).
 *
 * Built on `createImageBitmap` + `<canvas>` (universally available in
 * browsers, no dependency needed) rather than `argon2-browser`-style WASM.
 *
 * **NOT unit-testable under Vitest/jsdom**: jsdom implements neither
 * `createImageBitmap` nor a real `<canvas>` 2D rasterizer -- same category
 * of gap Android already disclosed for `ImageCompressor.kt` (not testable
 * on the host JVM either, no Robolectric dependency in that project). This
 * must be exercised via a real browser (Playwright/Chromium, which *does*
 * implement both) -- see this sprint's end-to-end verification, which picks
 * a real PNG and confirms the resulting attachment is smaller and still
 * renders correctly after decrypt.
 */
export const OUTPUT_MIME_TYPE = 'image/jpeg'
const MAX_DIMENSION_PX = 1600
const JPEG_QUALITY = 0.8

export interface CompressedImage {
  data: Uint8Array
  mimeType: string
}

/**
 * Downscales+re-encodes `file` as JPEG. Returns null if the browser can't
 * decode `file` as an image (caller should surface an error rather than
 * silently dropping the pick, or fall back to uploading the original bytes
 * -- see `stores/vault.ts`'s `uploadAttachment` for the fallback policy).
 */
export async function compressImage(file: File | Blob): Promise<CompressedImage | null> {
  if (typeof createImageBitmap !== 'function') {
    return null
  }

  let bitmap: ImageBitmap
  try {
    bitmap = await createImageBitmap(file)
  } catch {
    return null
  }

  try {
    const { width, height } = scaledDimensions(bitmap.width, bitmap.height)
    const canvas = document.createElement('canvas')
    canvas.width = width
    canvas.height = height
    const ctx = canvas.getContext('2d')
    if (!ctx) return null
    ctx.drawImage(bitmap, 0, 0, width, height)

    const blob = await canvasToBlob(canvas, OUTPUT_MIME_TYPE, JPEG_QUALITY)
    if (!blob) return null

    const buffer = await blob.arrayBuffer()
    return { data: new Uint8Array(buffer), mimeType: OUTPUT_MIME_TYPE }
  } finally {
    bitmap.close()
  }
}

function scaledDimensions(width: number, height: number): { width: number; height: number } {
  const longest = Math.max(width, height)
  if (longest <= MAX_DIMENSION_PX) {
    return { width, height }
  }
  const scale = MAX_DIMENSION_PX / longest
  return {
    width: Math.max(1, Math.round(width * scale)),
    height: Math.max(1, Math.round(height * scale)),
  }
}

function canvasToBlob(canvas: HTMLCanvasElement, type: string, quality: number): Promise<Blob | null> {
  return new Promise((resolve) => {
    canvas.toBlob((blob) => resolve(blob), type, quality)
  })
}
