import { Sun } from "lucide-react";
import { Route, Routes } from "react-router";
import { ApiError } from "./api/client.ts";
import { useMe } from "./api/queries.ts";
import { useHeartbeat } from "./live/useHeartbeat.ts";
import { useRadianceStream } from "./live/useRadianceStream.ts";
import { FeedPage } from "./pages/FeedPage.tsx";
import { FriendsPage } from "./pages/FriendsPage.tsx";
import { LoginPage } from "./pages/LoginPage.tsx";
import { MessengerPage } from "./pages/MessengerPage.tsx";
import { NotificationsPage } from "./pages/NotificationsPage.tsx";
import { OnboardingPage } from "./pages/OnboardingPage.tsx";
import { PacerPage } from "./pages/PacerPage.tsx";
import { PostPage } from "./pages/PostPage.tsx";
import { ProfilePage } from "./pages/ProfilePage.tsx";
import { Layout, Logo } from "./shell/Layout.tsx";
import { Empty } from "./ui/kit.tsx";

function Splash() {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center gap-4">
      <div className="animate-pop">
        <Logo />
      </div>
      <span className="size-6 animate-spin rounded-full border-2 border-accent border-r-transparent" aria-label="Loading" />
    </div>
  );
}

/**
 * Who R is decides what he sees: the login, onboarding (or a note to run genesis), or the network.
 * Signed in and onboarded, the stream and heartbeats keep the session live.
 */
export default function App() {
  const me = useMe();
  const ready = Boolean(me.data?.onboarded && me.data.actor);
  useRadianceStream(ready);
  useHeartbeat(ready);

  if (me.isPending) return <Splash />;
  if (me.error instanceof ApiError && me.error.status === 401) return <LoginPage />;
  if (me.isError || !me.data) {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <Empty icon={<Sun className="size-6" />} title="Radiance is unreachable">Start the backend, then refresh this page.</Empty>
      </div>
    );
  }
  const data = me.data;
  if (!data.worldReady) {
    return (
      <div className="flex min-h-screen items-center justify-center px-4">
        <Empty icon={<Sun className="size-6" />} title="The world hasn't been created yet">
          Run genesis first: <code className="rounded bg-panel-2 px-1.5 py-0.5">./gradlew genesis --args='--seed 7 --import'</code>
        </Empty>
      </div>
    );
  }
  if (!data.onboarded || !data.actor) return <OnboardingPage network={data.networkName} />;
  const actor = data.actor;
  return (
    <Layout me={actor} network={data.networkName}>
      <Routes>
        <Route path="/" element={<FeedPage me={actor} />} />
        <Route path="/posts/:id" element={<PostPage me={actor} />} />
        <Route path="/people/:handle" element={<ProfilePage me={actor} />} />
        <Route path="/friends" element={<FriendsPage />} />
        <Route path="/notifications" element={<NotificationsPage />} />
        <Route path="/messages" element={<MessengerPage me={actor} />} />
        <Route path="/messages/:threadId" element={<MessengerPage me={actor} />} />
        <Route path="/director/pacer" element={<PacerPage />} />
        <Route path="*" element={<FeedPage me={actor} />} />
      </Routes>
    </Layout>
  );
}
