import { useSyncExternalStore } from "react";

const KEY = "director";
const listeners = new Set<() => void>();
let on = read();

function read(): boolean {
  try {
    return localStorage.getItem(KEY) === "on";
  } catch {
    return false;
  }
}

/** Whether the Director's console shows anywhere (SPEC §5.16): off by default, to protect immersion. */
export function directorOn(): boolean {
  return on;
}

export function setDirector(value: boolean) {
  on = value;
  try {
    localStorage.setItem(KEY, value ? "on" : "off");
  } catch {
    // private mode: it just doesn't stick
  }
  listeners.forEach((l) => l());
}

/** For tests: back to what storage says. */
export function reloadDirector() {
  on = read();
  listeners.forEach((l) => l());
}

export function useDirector(): boolean {
  return useSyncExternalStore((l) => {
    listeners.add(l);
    return () => listeners.delete(l);
  }, directorOn);
}

/** Is this the shortcut that toggles the console: Ctrl+Shift+D, or Cmd+Shift+D? */
export function isDirectorShortcut(e: Pick<KeyboardEvent, "key" | "ctrlKey" | "metaKey" | "shiftKey">): boolean {
  return (e.ctrlKey || e.metaKey) && e.shiftKey && e.key.toLowerCase() === "d";
}
