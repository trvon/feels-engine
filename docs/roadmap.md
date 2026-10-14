# Roadmap

0.1.0 is tagged. In order from here:

1. Polish. Widen the invariants to the store and the rotation worker with a
   fake clock and resolver. Profile labeling and rotation on a 1,500-photo
   library. Fuzz the codecs and the scan pass harder.
2. A product audit against store readiness, graded with the ledger in
   `product/2026-10-13-product-review.md`, then the Play listing.
3. 0.2.0: motion over your photos. A live wallpaper engine
   (`WallpaperService.Engine`, audit section 4) that renders the current group
   itself: cross-fade on change, slow drift like the screen saver, parallax
   from launcher page offsets, depth zoom on the lock-to-home transition, and a
   crop per posture on foldables from `onSurfaceChanged`. Photos stay the
   content; no scene or video files. The static `setBitmap` path stays as the
   fallback for phones where a live wallpaper cannot reach the lock screen.
   Measures: battery per hour with the engine active against the static path,
   and how many users keep the engine after a week.

Open, not ordered:

- A bundled image-text embedding as the fallback when Gemini Nano is absent
  or refuses a photo. Labels-only matches words, not meaning.
- "Next photo" from outside the app, as a tile or a widget.
- Clock-safe photos on the lock screen.

Decided against: online photo sources, Google Photos API access (no longer
possible for a user's library) and exact alarms. The product review says why.
