import { X } from "lucide-react";
import { type ButtonHTMLAttributes, type ReactNode, useEffect, useRef } from "react";
import { Link } from "react-router";
import type { Actor } from "../api/types.ts";
import { colorFor, initials } from "./format.ts";

const SIZES = { xs: "size-6 text-[10px]", sm: "size-8 text-xs", md: "size-10 text-sm", lg: "size-14 text-lg", xl: "size-32 text-4xl" };

/**
 * A resident's (or R's) face: initials on a two-tone gradient drawn from their id, until M12 brings
 * real avatars. {@code ring} draws the brand gradient around it (R, or someone who's live right now).
 */
export function Avatar({ actor, size = "md", ring = false, link = true }: {
  actor: Pick<Actor, "id" | "name" | "handle">;
  size?: keyof typeof SIZES;
  ring?: boolean;
  link?: boolean;
}) {
  const a = colorFor(actor.id);
  const b = colorFor(actor.id + "~");
  const face = (
    <span
      aria-hidden="true"
      className={`${SIZES[size]} inline-flex shrink-0 select-none items-center justify-center rounded-full font-semibold tracking-wide text-white`}
      style={{ backgroundImage: `linear-gradient(135deg, ${a}, ${b})` }}
    >
      {initials(actor.name)}
    </span>
  );
  const body = ring ? (
    <span className="ring-brand inline-flex shrink-0 rounded-full">
      <span className="inline-flex rounded-full bg-panel p-[2px]">{face}</span>
    </span>
  ) : (
    face
  );
  if (!link) return body;
  return (
    <Link to={`/people/${actor.handle}`} aria-label={actor.name} className="inline-flex shrink-0 rounded-full">
      {body}
    </Link>
  );
}

export function Name({ actor, className = "" }: { actor: Pick<Actor, "name" | "handle">; className?: string }) {
  return (
    <Link to={`/people/${actor.handle}`} className={`font-semibold text-ink hover:underline ${className}`}>
      {actor.name}
    </Link>
  );
}

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & { variant?: "brand" | "soft" | "ghost"; busy?: boolean };

export function Button({ variant = "brand", busy, className = "", children, disabled, ...rest }: ButtonProps) {
  const look = {
    brand:
      "bg-brand text-white shadow-[0_6px_20px_-8px_rgb(240_56_107/0.7)] hover:brightness-110 active:scale-[0.97] disabled:opacity-50 disabled:shadow-none",
    soft: "bg-panel-2 text-ink hover:bg-line active:scale-[0.97] disabled:opacity-50",
    ghost: "text-muted hover:bg-panel-2 hover:text-ink active:scale-[0.97] disabled:opacity-50",
  }[variant];
  return (
    <button
      {...rest}
      disabled={disabled || busy}
      className={`inline-flex items-center justify-center gap-2 rounded-xl px-4 py-2 text-sm font-semibold transition duration-150 ${look} ${className}`}
    >
      {busy && <span className="size-3.5 animate-spin rounded-full border-2 border-current border-r-transparent" />}
      {children}
    </button>
  );
}

export function Card({ children, className = "" }: { children: ReactNode; className?: string }) {
  return <section className={`rounded-2xl border border-line bg-panel shadow-card ${className}`}>{children}</section>;
}

export function Skeleton({ className = "" }: { className?: string }) {
  return <div className={`skeleton ${className}`} />;
}

export function PostSkeleton() {
  return (
    <Card className="p-4">
      <div className="flex items-center gap-3">
        <Skeleton className="size-10 rounded-full" />
        <div className="flex-1 space-y-2">
          <Skeleton className="h-3 w-32" />
          <Skeleton className="h-2.5 w-20" />
        </div>
      </div>
      <Skeleton className="mt-4 h-3 w-full" />
      <Skeleton className="mt-2 h-3 w-4/5" />
      <Skeleton className="mt-5 h-8 w-full" />
    </Card>
  );
}

export function Segmented<T extends string>({ value, options, onChange, label }: {
  value: T;
  options: { value: T; label: string }[];
  onChange: (v: T) => void;
  label: string;
}) {
  return (
    <div role="tablist" aria-label={label} className="inline-flex rounded-xl bg-panel-2 p-1">
      {options.map((o) => (
        <button
          key={o.value}
          role="tab"
          aria-selected={o.value === value}
          onClick={() => onChange(o.value)}
          className={`rounded-lg px-3.5 py-1.5 text-sm font-semibold transition ${
            o.value === value ? "bg-panel text-ink shadow-card" : "text-muted hover:text-ink"
          }`}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}

export function Modal({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    document.addEventListener("keydown", onKey);
    ref.current?.querySelector<HTMLElement>("input, textarea, button")?.focus();
    return () => document.removeEventListener("keydown", onKey);
  }, [onClose]);
  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center bg-black/40 p-0 backdrop-blur-sm sm:items-center sm:p-4" onClick={onClose}>
      <div
        ref={ref}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        onClick={(e) => e.stopPropagation()}
        className="animate-rise w-full max-w-lg rounded-t-3xl border border-line bg-panel p-5 shadow-lift sm:rounded-3xl"
      >
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-lg font-bold">{title}</h2>
          <button onClick={onClose} aria-label="Close" className="rounded-full p-1.5 text-muted hover:bg-panel-2">
            <X className="size-5" />
          </button>
        </div>
        {children}
      </div>
    </div>
  );
}

export function Empty({ icon, title, children }: { icon: ReactNode; title: string; children?: ReactNode }) {
  return (
    <div className="flex flex-col items-center gap-2 px-6 py-12 text-center">
      <div className="bg-brand-soft flex size-14 items-center justify-center rounded-2xl text-accent">{icon}</div>
      <p className="mt-1 font-semibold">{title}</p>
      {children && <p className="max-w-sm text-sm text-muted">{children}</p>}
    </div>
  );
}

const TOKEN = /(@[a-z0-9_.]+[a-z0-9_]|https?:\/\/[^\s]+)/gi;

/** Post and comment text, with @mentions linked to profiles and URLs to their pages. */
export function RichText({ text, className = "" }: { text: string; className?: string }) {
  const parts = text.split(TOKEN);
  return (
    <p className={`whitespace-pre-wrap break-words ${className}`}>
      {parts.map((p, i) => {
        if (i % 2 === 0) return p;
        if (p.startsWith("@")) {
          return (
            <Link key={i} to={`/people/${p.slice(1).toLowerCase()}`} className="font-semibold text-accent hover:underline">
              {p}
            </Link>
          );
        }
        return (
          <a key={i} href={p} target="_blank" rel="noreferrer noopener" className="text-accent underline-offset-2 hover:underline">
            {p}
          </a>
        );
      })}
    </p>
  );
}
