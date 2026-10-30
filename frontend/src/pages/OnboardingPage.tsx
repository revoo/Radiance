import { ArrowLeft, ArrowRight, Check } from "lucide-react";
import { useState } from "react";
import { primeCsrf } from "../api/client.ts";
import { useOnboard } from "../api/queries.ts";
import type { Backstory } from "../api/types.ts";
import { Logo } from "../shell/Layout.tsx";
import { Button } from "../ui/kit.tsx";

/** Roster circles R can join (SPEC §5.9): any but family. */
const CIRCLES: { id: string; label: string }[] = [
  { id: "lakeshore", label: "Lakeshore" },
  { id: "prairie-path", label: "Prairie Path" },
  { id: "kinzie", label: "Kinzie" },
  { id: "uiuc", label: "UIUC" },
  { id: "berwyn-block", label: "Berwyn Block" },
  { id: "softball", label: "Softball" },
  { id: "online", label: "Online" },
];

const INTERESTS: { id: string; label: string; emoji: string }[] = [
  { id: "tech", label: "Tech", emoji: "💻" },
  { id: "fitness", label: "Fitness", emoji: "🏃" },
  { id: "food", label: "Food", emoji: "🍕" },
  { id: "music", label: "Music", emoji: "🎸" },
  { id: "movies_tv", label: "Movies & TV", emoji: "🎬" },
  { id: "gaming", label: "Gaming", emoji: "🎮" },
  { id: "outdoors", label: "Outdoors", emoji: "🌲" },
  { id: "travel", label: "Travel", emoji: "✈️" },
  { id: "cars", label: "Cars", emoji: "🚗" },
  { id: "pets", label: "Pets", emoji: "🐶" },
  { id: "politics", label: "Politics", emoji: "🗳️" },
  { id: "economy", label: "Economy", emoji: "📈" },
  { id: "science", label: "Science", emoji: "🔬" },
  { id: "health", label: "Health", emoji: "🩺" },
  { id: "home_improvement", label: "Home projects", emoji: "🔨" },
  { id: "fashion", label: "Fashion", emoji: "👟" },
  { id: "true_crime", label: "True crime", emoji: "🕵️" },
  { id: "celebrity", label: "Celebrity", emoji: "⭐" },
  { id: "local", label: "Local news", emoji: "📍" },
  { id: "personal_finance", label: "Money", emoji: "💸" },
  { id: "faith", label: "Faith", emoji: "🕊️" },
  { id: "parenting", label: "Parenting", emoji: "🍼" },
];

const BACKSTORIES: { id: Backstory; title: string; blurb: string; people: string }[] = [
  { id: "NEWCOMER", title: "Newcomer", blurb: "Just moved here. A few people know you; everyone else is a stranger.", people: "~4 friends" },
  { id: "REGULAR", title: "Regular", blurb: "You've been around a while. You know a handful of people well.", people: "~10 friends" },
  { id: "LOCAL", title: "Local", blurb: "Born and raised. Half the network has a story about you.", people: "~20 friends" },
];

function Chip({ on, onClick, children }: { on: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      type="button"
      aria-pressed={on}
      onClick={onClick}
      className={`flex items-center gap-1.5 rounded-full border px-3.5 py-2 text-sm font-semibold transition active:scale-95 ${
        on ? "border-transparent bg-brand text-white shadow-[0_6px_16px_-8px_rgb(240_56_107/0.8)]" : "border-line bg-panel hover:border-accent/40"
      }`}
    >
      {on && <Check className="size-3.5" aria-hidden="true" />}
      {children}
    </button>
  );
}

/**
 * Onboarding (SPEC §5.9): the roster pre-fills who R is (31, a software engineer in the metro); he picks
 * the rest. Three short steps, then the residents form their first impressions of him.
 */
export function OnboardingPage({ network }: { network: string }) {
  const [step, setStep] = useState(0);
  const [name, setName] = useState("");
  const [bio, setBio] = useState("");
  const [employer, setEmployer] = useState("");
  const [livesIn, setLivesIn] = useState("");
  const [circles, setCircles] = useState<string[]>([]);
  const [interests, setInterests] = useState<string[]>([]);
  const [backstory, setBackstory] = useState<Backstory>("LOCAL");
  const onboard = useOnboard();

  const toggle = (list: string[], set: (v: string[]) => void, id: string) =>
    set(list.includes(id) ? list.filter((x) => x !== id) : [...list, id]);

  const canNext = step === 0 ? name.trim().length > 1 && livesIn.trim().length > 1 : true;
  const finish = async () => {
    await primeCsrf();
    onboard.mutate({ name: name.trim(), bio: bio.trim(), employer: employer.trim(), livesIn: livesIn.trim(), circles, interests, backstory });
  };

  const field = "mt-1.5 w-full rounded-xl border border-line bg-panel-2 px-4 py-3 outline-none transition focus:border-accent focus:bg-panel";
  return (
    <div className="mx-auto flex min-h-screen max-w-2xl flex-col px-4 py-8">
      <Logo name={network} />
      <div className="mt-8 flex gap-2" aria-label={`Step ${step + 1} of 3`}>
        {[0, 1, 2].map((i) => (
          <span key={i} className={`h-1.5 flex-1 rounded-full transition-all ${i <= step ? "bg-brand" : "bg-line"}`} />
        ))}
      </div>

      <div key={step} className="animate-rise mt-8 flex-1">
        {step === 0 && (
          <>
            <h1 className="text-3xl font-extrabold tracking-tight">Who are you?</h1>
            <p className="mt-2 text-muted">You're 31, single, and you write software somewhere in the metro. Fill in the rest.</p>
            <label className="mt-6 block text-sm font-semibold" htmlFor="ob-name">Your name</label>
            <input id="ob-name" value={name} onChange={(e) => setName(e.target.value)} placeholder="Sam Rivera" className={field} />
            <div className="mt-4 grid gap-4 sm:grid-cols-2">
              <div>
                <label className="block text-sm font-semibold" htmlFor="ob-lives">Where you live</label>
                <input id="ob-lives" value={livesIn} onChange={(e) => setLivesIn(e.target.value)} placeholder="Logan Square" className={field} />
              </div>
              <div>
                <label className="block text-sm font-semibold" htmlFor="ob-work">Where you work</label>
                <input id="ob-work" value={employer} onChange={(e) => setEmployer(e.target.value)} placeholder="A startup in the Loop" className={field} />
              </div>
            </div>
            <label className="mt-4 block text-sm font-semibold" htmlFor="ob-bio">Bio</label>
            <textarea id="ob-bio" rows={3} value={bio} onChange={(e) => setBio(e.target.value)} placeholder="Coffee, code, and pretending I'll run a marathon." className={`${field} resize-none`} />
            <p className="mt-2 text-xs text-faint">Residents read this. First impressions count.</p>
          </>
        )}
        {step === 1 && (
          <>
            <h1 className="text-3xl font-extrabold tracking-tight">Your people</h1>
            <p className="mt-2 text-muted">Circles you belong to. Your first friends come from here.</p>
            <div className="mt-5 flex flex-wrap gap-2">
              {CIRCLES.map((c) => (
                <Chip key={c.id} on={circles.includes(c.id)} onClick={() => toggle(circles, setCircles, c.id)}>
                  {c.label}
                </Chip>
              ))}
            </div>
            <h2 className="mt-8 text-lg font-bold">What are you into?</h2>
            <div className="mt-3 flex flex-wrap gap-2">
              {INTERESTS.map((t) => (
                <Chip key={t.id} on={interests.includes(t.id)} onClick={() => toggle(interests, setInterests, t.id)}>
                  <span aria-hidden="true">{t.emoji}</span> {t.label}
                </Chip>
              ))}
            </div>
          </>
        )}
        {step === 2 && (
          <>
            <h1 className="text-3xl font-extrabold tracking-tight">How long have you been here?</h1>
            <p className="mt-2 text-muted">It decides how many people already know you.</p>
            <div className="mt-6 grid gap-3 sm:grid-cols-3">
              {BACKSTORIES.map((b) => (
                <button
                  key={b.id}
                  type="button"
                  aria-pressed={backstory === b.id}
                  onClick={() => setBackstory(b.id)}
                  className={`rounded-2xl border-2 p-4 text-left transition active:scale-[0.98] ${
                    backstory === b.id ? "border-accent bg-accent-soft shadow-lift" : "border-line bg-panel hover:border-accent/40"
                  }`}
                >
                  <span className="text-lg font-bold">{b.title}</span>
                  <span className="mt-1 block text-sm text-muted">{b.blurb}</span>
                  <span className="mt-3 inline-block rounded-full bg-panel px-2.5 py-1 text-xs font-bold text-accent">{b.people}</span>
                </button>
              ))}
            </div>
          </>
        )}
      </div>

      {onboard.isError && <p role="alert" className="mt-4 text-sm text-bad">Something went wrong. Try again.</p>}
      <div className="mt-8 flex items-center justify-between">
        <Button variant="ghost" onClick={() => setStep((s) => s - 1)} disabled={step === 0}>
          <ArrowLeft className="size-4" /> Back
        </Button>
        {step < 2 ? (
          <Button onClick={() => setStep((s) => s + 1)} disabled={!canNext} className="px-6">
            Next <ArrowRight className="size-4" />
          </Button>
        ) : (
          <Button onClick={finish} busy={onboard.isPending} className="px-6">
            Move in <ArrowRight className="size-4" />
          </Button>
        )}
      </div>
    </div>
  );
}
