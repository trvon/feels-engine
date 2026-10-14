# Roadmap

In order:

1. Polish. Widen the invariants to the store and the rotation worker with a
   fake clock and resolver. Profile labeling and rotation on a 1,500-photo
   library. Fuzz the codecs and the scan pass harder.
2. A product audit against store readiness, graded with the ledger in
   `product/2026-10-13-product-review.md`.
3. The store listing.

Open, not ordered:

- A bundled image-text embedding as the fallback when Gemini Nano is absent
  or refuses a photo. Labels-only matches words, not meaning.
- A live wallpaper engine for motion and per-posture crops (audit section 4).
- "Next photo" from outside the app, as a tile or a widget.
- Clock-safe photos on the lock screen.

Decided against: online photo sources, Google Photos API access (no longer
possible for a user's library) and exact alarms. The product review says why.
