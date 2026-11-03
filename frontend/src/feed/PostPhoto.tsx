import { useState } from "react";
import type { Media } from "../api/types.ts";
import { Modal } from "../ui/kit.tsx";

/** A post's photo: shimmers while it loads, and tapping it shows it bigger. */
export function PostPhoto({ media, previewUrl }: { media: Media | null | undefined; previewUrl?: string }) {
  const [loaded, setLoaded] = useState(false);
  const [failed, setFailed] = useState(false);
  const [open, setOpen] = useState(false);
  const src = previewUrl ?? media?.url;
  if (!src || failed) return null;
  const alt = media?.description ?? "A photo";
  const ratio = media?.width && media?.height ? `${media.width} / ${media.height}` : undefined;
  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        aria-label={`See this photo bigger: ${alt}`}
        className={`relative mb-1 block w-full overflow-hidden bg-panel-2 ${loaded ? "" : "animate-pulse"}`}
        style={{ aspectRatio: ratio, minHeight: ratio ? undefined : "12rem" }}
      >
        <img
          src={src}
          alt={alt}
          loading="lazy"
          onLoad={() => setLoaded(true)}
          onError={() => setFailed(true)}
          className={`max-h-[36rem] w-full object-cover transition-opacity duration-500 ${loaded ? "opacity-100" : "opacity-0"}`}
        />
      </button>
      {open && (
        <Modal title="Photo" onClose={() => setOpen(false)}>
          <img src={src} alt={alt} className="max-h-[75vh] w-full rounded-xl object-contain" />
        </Modal>
      )}
    </>
  );
}
