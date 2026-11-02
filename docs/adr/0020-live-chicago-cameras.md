# 0020 — A live window on Chicago, from IDOT's public cameras

Status: accepted · 2026-11-02 · R's idea, on top of SPEC §5.12 (real weather and news)

## Context
R wants the left rail to show live views of Chicago, for the feeling that the world is really out there.
Most good-looking cams are private: EarthCam's business includes licensing its feeds, and the Illinois Sea Grant
buoy cam asks for written permission. IDOT publishes "Illinois Gateway Traffic Cameras" as open data, with no key,
listed as CC BY-SA 2.0. Several of its downtown cameras are 1080p, updated every few minutes, and frame the skyline.

## Decision
- **Source:** IDOT's public feature service. A hand-picked list of cameras lives in config
  (`radiance.cameras.spots`), because many aren't worth showing and some are partly blocked.
- **Fetched by our server.** It asks for the list every 5 minutes and each image at most once a minute however many
  tabs are open, and it only ever fetches https from the one configured host. Viewers ask for a camera by our id, never
  by URL, so nothing they send decides where the server connects.
- **Honest about time.** The tile shows each image's real age, taken from the image itself; it never just says "live".
  Cameras the source flags as too old are skipped, and a failed fetch serves the last image for up to 15 minutes.
- **Hidden when the world's clock has moved off real time** (a pause or a fast-forward), so it can't show daylight
  during an in-game night.
- **Shown whole, with the credit** the license asks for. No cropping: the burned-in time and IDOT logo stay.
- **Off by default,** on in `prod`: tests and offline never call out.

## Consequences
- These are road cameras: no Wrigley, no Bean. The list can grow if R licenses or finds better ones.
- The license is on the dataset's listing, and it doesn't say outright that it covers the images. That's fine for a
  private, single-player game; look again before Radiance is ever public.
- A camera IDOT renames or moves drops out of the tile quietly, since spots match on its snapshot's file name.
