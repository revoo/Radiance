import { Sun } from "lucide-react";
import { Component, type ErrorInfo, type ReactNode } from "react";

/** A bug in one screen shouldn't leave R looking at a blank page: he gets a way back in. */
export class ErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  override state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  override componentDidCatch(error: Error, info: ErrorInfo) {
    console.error("The app crashed", error, info.componentStack);
  }

  override render() {
    if (!this.state.failed) return this.props.children;
    return (
      <div role="alert" className="flex min-h-screen flex-col items-center justify-center gap-3 px-6 text-center">
        <span className="bg-brand flex size-12 items-center justify-center rounded-2xl text-white"><Sun className="size-6" aria-hidden="true" /></span>
        <h1 className="text-xl font-extrabold tracking-tight">Something broke on this screen</h1>
        <p className="max-w-sm text-sm text-muted">The world is fine and nothing was lost. Reload to get back to it.</p>
        <button onClick={() => window.location.assign("/")} className="bg-brand rounded-xl px-4 py-2 text-sm font-semibold text-white">Back to the feed</button>
      </div>
    );
  }
}
