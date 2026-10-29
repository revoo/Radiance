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

For every brief return one item with:
- `briefId`: the brief's id, exactly once each.
- `text`: the post, comment or message itself — nothing around it.
- `gist`: what happened, in 15 words or fewer, plain and neutral ("Jake mocked Jesse's new car").
- `recapLine`: if the brief's `recapLine` is true, one gossipy line a friend would text about it;
  otherwise an empty string.
- `introducedFacts`: any new detail you made up about a person or their things that later posts should
  keep ("bought a used tile saw for $80"). Mark one `major` if it changes a life — a job, a move, a
  relationship, a death, money that matters. Don't introduce major facts; small ones are fine.

Return JSON matching the schema.
