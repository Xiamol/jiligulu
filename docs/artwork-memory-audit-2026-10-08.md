# Artwork and media audit

The PNG headers show about 6 MiB of ARGB pixels per full scene. The previous process-wide `ConcurrentHashMap` kept every decoded scene strongly reachable; seven full paintings and sampled decorations could accumulate about 55 MiB. This is a static pixel estimate, not a device RAM measurement. The replacement cache keeps at most 12 MiB globally, drops references on background/pressure, and never recycles images retained by visible Compose content. Cold decorative decodes run on IO.

Photo decoding keeps the same target size and EXIF orientation, performs target scaling during decode, and combines any remaining scale/rotation into one matrix. Private render intermediates are released in `finally`, including allocation failures. A mutex queues full poster renders instead of producing several large canvases concurrently.

Android 29+ poster fonts use the packaged Noto variable font through `Font.Builder(Resources, resId)` with the same explicit 400/650 weight axis and system fallback. Android 26–28, or a modern resource-font failure, retain the file-based builder. The legacy cache copy is 17,772,300 bytes. This change avoids creating it on the modern successful path; it does not delete already existing copies or claim all existing user caches became smaller. See the [Android Font.Builder reference](https://developer.android.com/reference/android/graphics/fonts/Font.Builder) and [CustomFallbackBuilder reference](https://developer.android.com/reference/android/graphics/Typeface.CustomFallbackBuilder).

Cleanup remains limited to caller-owned draft paths, with active commit claims and references from live/deleted bills, wishes, cards, and all non-DELETED DRAFT photo payloads. Photos attach to individual bill drafts; the transient proposal to attach them to USER messages was withdrawn before release. Chat queries load only kind/status/payload, not message content. A failed reference query or malformed live attachment preserves candidates. Engine models, licenses, user files, and device caches were not swept.

Seven obsolete packaged PNGs were removed, totaling 14,476,165 bytes:

| File under drawable-nodpi | Bytes | Main/test Kotlin or XML name references |
| --- | ---: | ---: |
| secret_wood_surface_v1.png | 2,065,776 | 0 |
| wish_lucky_stars_atlas_v2.png | 2,151,146 | 0 |
| wish_puffy_stars_atlas_v3.png | 2,207,212 | 0 |
| wish_shelf_lavender_v2.png | 1,653,988 | 0 |
| wish_shelf_room_anime.png | 1,982,090 | 0 |
| world_interactive_room_v1.png | 2,292,603 | 0 |
| world_interactive_room_v2.png | 2,123,350 | 0 |

Each name was searched with `rg` in `app/src` Kotlin/XML. Skin and poster resources use static resource IDs. The only production `getIdentifier` call resolves an Android `dimen`, not artwork names; no dynamic drawable name lookup was found. Current scene versions and all active assets remain packaged.
