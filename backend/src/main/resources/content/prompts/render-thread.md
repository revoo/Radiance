<today>
{{today}}
</today>

<people>
{{people}}
</people>

<canon>
{{canon}}
</canon>

<thread>
{{thread}}
</thread>

<briefs>
{{briefs}}
</briefs>

Write each brief as its author would actually type it, in order: their voice card, their state, their
constraints and what they know. Rude, petty, crude or prejudiced if that's who they are and the stance
says so; warm and goofy if that's who they are. Never soften, disclaim, moralize or add a lesson.

Keep it true:
- Their job, family and city are the ones in the cast directory: never give them another job, boss or coworkers. Don't invent a
  spouse, child or parent they don't have. If someone's retired or doesn't have a job, they don't have a workday.
- A voice card is how they sound, not a script. Most posts use no catchphrase at all, and nobody signs off with one out of habit.
- Food, family and traditions come from their own heritage in the directory, not everyone else's.
- It's the date, hour, weather and season in <today>. Say "this morning" only if it's morning now. No outdoor summer things in the
  cold, and nobody goes to or watches a team that's off season.
- A photo post's text is the caption they typed. Never describe the photo itself.

For every brief return one item with:
- `briefId`: the brief's id, exactly once each.
- `text`: the post, comment or message itself — nothing around it.
- `gist`: what happened, in 15 words or fewer, plain and neutral ("Jake mocked Jesse's new car").
- `recapLine`: if the brief's `recapLine` is true, one gossipy line a friend would text about it;
  otherwise an empty string.
- `introducedFacts`: each with the person's @handle as `subject`: any new detail you made up about a person or their things that later posts should
  keep ("bought a used tile saw for $80"). Always include every newly named person and how they're related
  ("nephew Marcus, 7"). Mark one `major` if it changes a life — a job, a move, a
  relationship, a death, money that matters. Don't introduce major facts; small ones are fine.

Return JSON matching the schema.
