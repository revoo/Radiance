/** What a like-meter's number feels like (SPEC §5.3, §8). */
export function meterLabel(value: number): string {
  if (value <= -60) return "Can't stand you";
  if (value <= -25) return "Dislikes you";
  if (value < -5) return "Not a fan";
  if (value <= 10) return "Neutral";
  if (value <= 30) return "Friendly";
  if (value <= 55) return "Likes you";
  if (value <= 80) return "Really likes you";
  return "Adores you";
}

/** A Sims-style bar from −100 to +100, red to green. */
export function LikeMeter({ value, name, compact = false }: { value: number; name: string; compact?: boolean }) {
  const clamped = Math.max(-100, Math.min(100, value));
  const label = meterLabel(clamped);
  return (
    <div className={compact ? "w-32" : "w-full max-w-xs"}>
      {!compact && (
        <div className="mb-1 flex items-baseline justify-between gap-2 text-xs font-semibold">
          <span>{label}</span>
          <span className="tabular-nums text-muted">
            {clamped > 0 ? "+" : ""}
            {Math.round(clamped)}
          </span>
        </div>
      )}
      <div
        role="meter"
        aria-label={`How ${name} feels about you`}
        aria-valuemin={-100}
        aria-valuemax={100}
        aria-valuenow={Math.round(clamped)}
        aria-valuetext={label}
        title={compact ? `${label} (${Math.round(clamped)})` : undefined}
        className="relative h-2 rounded-full"
        style={{ background: "linear-gradient(90deg, #e03131 0%, #f08c00 35%, #adb5bd 50%, #94d82d 68%, #2f9e44 100%)" }}
      >
        <span
          className="absolute top-1/2 size-3.5 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-white bg-ink shadow transition-[left] duration-500"
          style={{ left: `${(clamped + 100) / 2}%` }}
        />
      </div>
    </div>
  );
}
