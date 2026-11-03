# V1 readiness: what we test before R's acceptance test

R is the only user. The bar is what it would take to show this to a room: R sits down, logs in, and within ten minutes believes thirty people live here and that they notice them. Anything that
breaks that belief, or anything that would break in front of an audience, is a blocker. R doesn't mind what generated photos contain as long as they look real.

Status: ☐ not run · ☑ passed · ✗ found a problem (see Findings)

Test worlds run on the server in a separate container with their own data folder, login and local-only media, reached through an SSH
tunnel. They never touch the live world's data, backups or R2. Spending limit for this pass: $3 of Haiku and $1 of image credits.

## 1. Believable writing (real Haiku, read by eye and checked by script)
- ☑ Canon: no post contradicts a resident's job, age, city, spouse, children or backstory
- ☑ The dead stay dead (Pete, for one), exes stay exes, nobody marries or moves without a storyline
- ☑ Secrets: nobody mentions a secret romance they aren't in on; constraints like "avoiding" hold
- ☐ Thread logic: replies answer what was said, a person doesn't argue with themselves, names match who's in the thread
- ☑ Nobody ever writes as R, or claims a history with R that didn't happen
- ☐ Voices: each resident is recognizable with the name covered; catchphrases don't get overused
- ☐ Repetition: no phrase, opener or event detail repeats across residents more than real people would
- ☑ No LLM-isms, moralizing, softening or placeholder text anywhere
- ☑ News takes match each person's politics and refer to the actual headline
- ☐ Timing: posts fit the hour, day, weather and season; night-shift people post at night

## 2. The feed feels alive
- ☑ Most posts get reactions, many get comments, a few get real threads
- ☑ Posting rates vary: chatty people, lurkers, nobody spamming
- ☑ Storylines are visible and move: a reader could say what's going on with someone
- ☑ The two-week history reads as a past, and the first screen is worth reading

## 3. R playing, on the real path (browser, real Haiku)
- ☑ Log in, log out, onboard from scratch; a wrong password fails cleanly
- ☑ Post text: first reaction within seconds, comments that respond to what R actually said
- ☐ Comment on a resident's post, and reply in a thread
- ☑ Post a photo: upload, the vision description, responses that mention what's in it
- ☑ DMs: typing, replies in voice, memory of earlier messages, seen state
- ☑ Provoke: rude, flirty and pretentious R all get believable, different reactions; meters move
- ☐ Notifications, friend requests, like-meter pulses, profiles, photo grids, avatars opening full size
- ☑ Live cameras, weather, news rail, online list

## 4. A day passing
- ☑ Nightly batch: plans, renders and airs with real prose, images and cost within budget
- ☑ Fast-forward six hours, pause, resume, catch up: time, feed and cameras stay consistent
- ☑ Inject a breakup and a feud from the Director: both sides show it, friends react, prose follows
- ☐ Tomorrow's posts remember today's: what R said comes back

## 5. Images
- ☑ Klein 4B and FLUX 3 run through the app, not just in test scripts
- ☑ Photos match their captions and look real; high-value ones really use FLUX 3
- ☑ 30 avatars load, faces crop right everywhere, the full photo opens
- ☐ Out of neurons or credits: falls back or waits, nothing breaks

## 6. Things going wrong (an audience will find them)
- ☑ Anthropic down or rate limited: the site stays up, nothing shows an error or placeholder to a viewer
- ☐ Budget caps stop spending and resume later
- ☑ Restart mid-session and mid-batch: no lost or doubled posts
- ☑ Backup reaches R2 and a restore brings the world back
- ☑ Bad model output (invalid JSON, wrong ids) is caught

## 7. Looks and works
- ☑ Desktop at 1080p and 2560×1440, phone width, light and dark, no overflow or clipped text
- ☐ No console errors, broken images or dead buttons on any page
- ☐ Loading and empty states look intended; first load is fast over the real domain
- ☐ Director's console: every tab opens and works, and stays hidden until asked for

## 8. Safe
- ☑ Login required everywhere, security headers in place, no secrets in pages or logs
- ☐ Content hard lines hold: adults only, non-explicit, no invented quotes about real people, blacklisted arcs absent
- ☑ Cost story is true: what a day costs, measured, with the caps

## 9. Ready for R's UAT
- ☐ A fresh live world that opens well, with a good story in progress

## Findings
Fixed, each with a regression test unless noted:
- **Invented details were being thrown away.** The model names people "Lukas Petrauskas", the code only knew @lukas.petrauskas, so 1 fact in 60 stuck, and Andre's nephew became his son. Facts now resolve by name: 60-odd stick per world.
- **Cloudflare refused `seed`:** every generated photo would have failed.
- **Friends' posts got no comments:** the median was 0, now 2, with R's bands unchanged.
- **R's first posts got one comment, always from the same friend:** now at least two, from different people.
- **Life events swept the cast:** seven couches in a fortnight, four Michigan weekends. Rare events are now spaced across the cast.
- **Photos didn't match posts, then painted the post on the picture.** They now come from one sentence the writer gives of what the camera sees.
- **"No X" rules made photos worse:** "no faces" blurred faces. Only "no children" remains, per R.
- **A failed live render waited for the nightly batch:** it now retries each minute.
- Also: residents avoiding themselves; a friend retelling someone's story as their own; an ex consoling their ex on the breakup post; Cubs games in November; retired Gary's workdays; catchphrases in every post; pierogi at every table; the model writing "no wait, I'm Lisa"; a filter refusing a photo of a table saw; onboarding's dark Next button. The last few prompt-only changes (catchphrases, pierogi) were checked by rereading worlds rather than by a test.

Known limits, none a blocker:
- A stray garbled word can still appear in a photo (1 in 12 in the last world). R prefers that to a fake-looking picture.
- Recurring things match by description, not a reference image: Lucy reads as the same dog, but not pixel-identical.
- Avatars are approved files; everyday photos can't show a resident's exact face.
- Restarting the app logs you out.
- Fast-forwarding past a news day can leave a stale take ("midterms are today" the next day). Normal play doesn't fast-forward.
- Haiku occasionally writes a slightly off line in a long argument. Expect about one per long thread.
- FLUX 3 spending isn't in the Costs tab; check Cloudflare's AI Gateway credits.

Not run, on purpose: live contract tests; R's own taste on the final live world; the budget caps' real-money path, which is covered by automated tests only.
