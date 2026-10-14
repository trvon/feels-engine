# Roadmap

In the owner's order.

1. Polish: widen the invariants to the store and the rotation worker (fake
   clock and resolver), profile labeling and rotation on a 1,500-photo
   library, fuzz the codecs and the scan pass harder.
2. Product audit against store readiness, graded with the research ledger in
   `product/2026-10-13-product-review.md`.
3. Store listing.

Open items, not ordered:

- A bundled image-text embedding as the fallback when Gemini Nano is absent or
  refuses a photo; labels-only matches words, not meaning.
- Live wallpaper engine for motion and per-posture crops (audit section 4).
- "Next photo" from outside the app: tile or widget.
- Clock-safe photos on the lock screen.

Decided against: online photo sources, Google Photos API access (no longer
possible for a user's library), exact alarms. See the product review.
