/**
 * Bug condition exploration test — Bug 3 (cross-cutting MinIO bypass) F6.
 *
 * POST-FIX INVARIANT (Property F6):
 *   For any AI streaming response that emits attachments, every attachment
 *   URL SHALL come from MinIO presigned URLs (matching MINIO_PRESIGNED_PATTERN)
 *   OR an asset-service `static/resources` entry whose `kind=minio` —
 *   NOT from the static frontend `media-catalog.ts` table.
 *
 *   Concretely, post-fix `AiMascotDock.vue` must:
 *     (a) NOT directly import `videos / images` from `media-catalog` for
 *         attachment payloads (decorative ambient video is allowed but
 *         must not flow into `Attachment[]`).
 *     (b) Reference an asset-service / presigned-URL fetch (e.g.
 *         `getPresignedUrl`, `X-Amz-Signature`, `minio`-tagged resource).
 *     (c) Drop the `dynamicMedia.pickAnyVisual(...)` fallback that mints
 *         attachments from the catalog when the SSE stream provides none.
 *
 * Counterexample we expect to record on the CURRENT (unfixed) code:
 *   - `AiMascotDock.vue` imports `{ videos, images }` from media-catalog.
 *   - `media-catalog.ts` only exposes `/media/...` paths.
 *   - The dock's `done` SSE handler injects 3 `dynamicMedia.pickAnyVisual`
 *     attachments — every URL ends up in the catalog set, never matches
 *     the MinIO presigned pattern.
 *
 * Validates: Requirements 1.4, 2.4, 3.1 (post-fix form).
 */
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const DOCK_PATH = resolve(__dirname, '../../src/components/ai/AiMascotDock.vue')
const DOCK_SOURCE = readFileSync(DOCK_PATH, 'utf8')

const CATALOG_PATH = resolve(__dirname, '../../src/assets/media-catalog.ts')
const CATALOG_SOURCE = readFileSync(CATALOG_PATH, 'utf8')

/** SigV4-style presigned URL emitted by the MinIO Java SDK. */
const MINIO_PRESIGNED_PATTERN =
  /\?(?:[^&\s]*&)*X-Amz-Signature=[A-Fa-f0-9]+/

describe('[bugfix-exploration] AiMascotDock attachment sourcing', () => {
  it('post-fix: AiMascotDock must NOT import { videos, images } from media-catalog for attachments', () => {
    // The whole import line is fingerprintable. Decorative ambient video may
    // remain (regression-prevention 3.8), but bringing both `videos` AND
    // `images` from media-catalog into the chat dock is the smoking gun of
    // the static-attachment path.
    const importsBoth =
      /from\s+['"][^'"]*media-catalog['"]/.test(DOCK_SOURCE) &&
      /import\s*\{\s*[^}]*\bvideos\b[^}]*\bimages\b[^}]*\}\s*from\s*['"][^'"]*media-catalog['"]/
        .test(DOCK_SOURCE)
    // Counterexample on current code: importsBoth === true → assertion FAILS.
    expect(importsBoth).toBe(false)
  })

  it('post-fix: AiMascotDock must reference a real MinIO/asset-service presigned path', () => {
    // After the fix, the dock either calls `/api/v1/assets/static/resources?kind=minio`
    // or carries presigned URLs straight from SSE `attachments`. Either way,
    // SOMETHING in the source must mention the MinIO presigned signal.
    const hasPresignedMarker =
      /X-Amz-Signature/.test(DOCK_SOURCE) ||
      /getPresignedUrl/.test(DOCK_SOURCE) ||
      /kind=minio/.test(DOCK_SOURCE) ||
      /presignedUrl/i.test(DOCK_SOURCE)
    // Counterexample on current code: hasPresignedMarker === false → FAILS.
    expect(hasPresignedMarker).toBe(true)
  })

  it('post-fix: AiMascotDock must NOT mint attachments via dynamicMedia.pickAnyVisual', () => {
    // The `pickAnyVisual` fallback is the synthesizer of catalog-rooted
    // attachments. After the fix it must not be called from the streaming
    // path; if it remains for decorative visuals, it must not feed into
    // `reply.attachments`.
    const hasPickAttachmentLink =
      /reply\.attachments\s*=\s*atts/.test(DOCK_SOURCE) &&
      /dynamicMedia\.pickAnyVisual/.test(DOCK_SOURCE)
    // Counterexample on current code: pattern matches → assertion FAILS.
    expect(hasPickAttachmentLink).toBe(false)
  })

  it('post-fix property: any attachment URL must match MinIO presigned pattern, never a /media/ static path', () => {
    // Post-fix invariant on the SOURCE: the streaming attachment path must
    // pipe `minioMediaFetchTool` output (which carries MinIO presigned URLs
    // including `X-Amz-Signature`) into `reply.attachments`. There must be
    // NO `dynamicMedia.pickAnyVisual` -> `reply.attachments` minting, and the
    // dock must reference the presigned-URL signal.

    // 1) No /media/ static URL is ever assigned to reply.attachments.
    //    We look for `reply.attachments = [...]` lines that contain a /media/ literal.
    const replyAttachLines = DOCK_SOURCE
      .split('\n')
      .filter((l) => /reply\.attachments\s*=/.test(l) || /\/media\//.test(l))
    const offending = replyAttachLines.filter(
      (l) => /reply\.attachments\s*=/.test(l) && /\/media\//.test(l),
    )
    expect(offending).toEqual([])

    // 2) Source explicitly references the MinIO presigned-URL signal
    //    OR the asset-service presigned endpoint.
    const refersToPresigned =
      MINIO_PRESIGNED_PATTERN.test(DOCK_SOURCE) ||
      /X-Amz-Signature/.test(DOCK_SOURCE) ||
      /presignedUrl/i.test(DOCK_SOURCE) ||
      /minioMediaFetchTool/.test(DOCK_SOURCE)
    expect(refersToPresigned).toBe(true)

    // 3) Catalog file is still the static `/media/` table — we keep this
    //    cross-check so CI can show *which* file the static URLs come from
    //    (used only for decorative ambient backgrounds post-fix).
    expect(CATALOG_SOURCE).toMatch(/const\s+PUBLIC\s*=\s*['"]\/media['"]/)
  })
})
