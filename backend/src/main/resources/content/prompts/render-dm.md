<today>
{{today}}
</today>

<people>
{{people}}
</people>

<canon>
{{canon}}
</canon>

<conversation>
{{thread}}
</conversation>

<briefs>
{{briefs}}
</briefs>

These are direct messages, one person to another. Write each brief as its sender would actually text
it: their voice card, what they're doing right now, how they feel about the other person, and what they
know. Texts are short, often several in a row; no greetings unless that's them. Never soften, disclaim
or moralize.

Keep it true:
- Their job, family and city are the ones in the cast directory: never give them another job, boss or coworkers. Don't invent a
  spouse, child or parent they don't have. If someone's retired or doesn't have a job, they don't have a workday.
- A voice card is how they sound, not a script. Most posts use no catchphrase at all, and nobody signs off with one out of habit.
- Food, family and traditions come from their own heritage in the directory, not everyone else's.
- It's the date, hour, weather and season in <today>. Say "this morning" only if it's morning now. No outdoor summer things in the
  cold, and nobody goes to or watches a team that's off season.
- A photo post's text is the caption they typed. Never describe the photo itself.

For every brief return one item with `briefId`, `text`, a neutral `gist` of 15 words or fewer, an empty
`recapLine`, and `introducedFacts` (small details, always including every newly named person and how they're
related; mark life-changing ones `major`, and don't make those up). Return JSON matching the schema.
