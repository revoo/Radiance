import { api, apiPut } from "../api/client.ts";
import type { Media } from "../api/types.ts";

const FULL = 1600;
const THUMB = 400;
const QUALITY = 0.85;
/** Bigger than any phone photo. Past this, something's probably wrong with the file. */
export const MAX_SOURCE_BYTES = 40 * 1024 * 1024;

/** A size to fit inside {@code max} on its long side, never scaling up. */
export function fitWithin(width: number, height: number, max: number): { width: number; height: number } {
  const scale = Math.min(1, max / Math.max(width, height));
  return { width: Math.round(width * scale), height: Math.round(height * scale) };
}

async function toJpeg(image: ImageBitmap, max: number): Promise<Blob> {
  const size = fitWithin(image.width, image.height, max);
  const canvas = document.createElement("canvas");
  canvas.width = size.width;
  canvas.height = size.height;
  const context = canvas.getContext("2d");
  if (!context) throw new Error("This browser can't resize photos");
  context.drawImage(image, 0, 0, size.width, size.height);
  return new Promise((resolve, reject) => {
    canvas.toBlob((blob) => (blob ? resolve(blob) : reject(new Error("Couldn't read that photo"))), "image/jpeg", QUALITY);
  });
}

/**
 * Resizes a photo here in the browser and sends both sizes (SPEC §5.13). Re-encoding also strips what a camera hides in
 * a file, like where it was taken. Resolves once the server has looked at it, so a post made with it knows what's in it.
 */
export async function uploadPhoto(file: File): Promise<Media> {
  if (!file.type.startsWith("image/")) throw new Error("That file isn't a photo");
  if (file.size > MAX_SOURCE_BYTES) throw new Error("That photo is too large");
  let image: ImageBitmap;
  try {
    image = await createImageBitmap(file);
  } catch {
    throw new Error("Couldn't read that photo. JPEG, PNG or WebP work best");
  }
  try {
    const [full, thumb] = await Promise.all([toJpeg(image, FULL), toJpeg(image, THUMB)]);
    const size = fitWithin(image.width, image.height, FULL);
    const started = await api<{ mediaId: string; putUrl: string; thumbPutUrl: string }>("/api/media/uploads", {
      json: { mime: "image/jpeg", bytes: full.size, width: size.width, height: size.height },
    });
    await Promise.all([apiPut(started.putUrl, full), apiPut(started.thumbPutUrl, thumb)]);
    return await api<Media>(`/api/media/${started.mediaId}/complete`, { method: "POST" });
  } finally {
    image.close();
  }
}
