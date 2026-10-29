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

For every brief return one item with `briefId`, `text`, a neutral `gist` of 15 words or fewer, an empty
`recapLine`, and `introducedFacts` (small details only; mark life-changing ones `major`, and don't make
those up). Return JSON matching the schema.
