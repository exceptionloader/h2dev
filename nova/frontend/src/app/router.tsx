import { createBrowserRouter, Navigate } from "react-router";
import { AppShell } from "@/components/layout/AppShell";
import { ActivitiesPage, ServerSessionsPage } from "@/features/activity/SessionsPages";
import { SessionPage } from "@/features/activity/SessionPage";
import { SpacePage } from "@/features/activity/SpacePage";
import { GuestOnly, RequireAuth } from "@/features/auth/guards";
import { LoginPage } from "@/features/auth/LoginPage";
import { PasswordResetConfirmPage, PasswordResetRequestPage } from "@/features/auth/PasswordResetPages";
import { RegisterPage } from "@/features/auth/RegisterPage";
import { ChannelPage } from "@/features/channels/ChannelPage";
import { DiscoverPage } from "@/features/discover/DiscoverPage";
import { HomePage } from "@/features/home/HomePage";
import { InvitePage } from "@/features/invites/InvitePage";
import { ReportsPage } from "@/features/moderation/ReportsPage";
import { AuditLogPage } from "@/features/servers/AuditLogPage";
import { ServerLayout } from "@/features/servers/ServerLayout";
import { ServerOverviewPage } from "@/features/servers/ServerOverviewPage";
import { ServerSettingsPage } from "@/features/servers/ServerSettingsPage";
import { SettingsPage } from "@/features/settings/SettingsPage";
import { FriendsPage } from "@/features/social/FriendsPage";
import { MessagesPage } from "@/features/social/MessagesPage";

export const routes = [
  {
    element: <GuestOnly />,
    children: [
      { path: "/anmelden", element: <LoginPage /> },
      { path: "/registrieren", element: <RegisterPage /> },
      { path: "/passwort-vergessen", element: <PasswordResetRequestPage /> },
    ],
  },
  // Reachable logged in or out (link from the reset e-mail).
  { path: "/passwort-zuruecksetzen", element: <PasswordResetConfirmPage /> },
  {
    element: <RequireAuth />,
    children: [
      {
        element: <AppShell />,
        children: [
          { path: "/", element: <HomePage /> },
          { path: "/entdecken", element: <DiscoverPage /> },
          { path: "/aktivitaeten", element: <ActivitiesPage /> },
          { path: "/sessions/:sessionId", element: <SessionPage /> },
          { path: "/freunde", element: <FriendsPage /> },
          { path: "/nachrichten", element: <MessagesPage /> },
          { path: "/nachrichten/:dmId", element: <MessagesPage /> },
          { path: "/einladung/:code", element: <InvitePage /> },
          { path: "/einstellungen", element: <SettingsPage /> },
          {
            path: "/server/:serverId",
            element: <ServerLayout />,
            children: [
              { index: true, element: <ServerOverviewPage /> },
              { path: "kanal/:channelId", element: <ChannelPage /> },
              { path: "space/:spaceId", element: <SpacePage /> },
              { path: "sessions", element: <ServerSessionsPage /> },
              { path: "einstellungen", element: <ServerSettingsPage /> },
              { path: "meldungen", element: <ReportsPage /> },
              { path: "audit-log", element: <AuditLogPage /> },
            ],
          },
        ],
      },
    ],
  },
  { path: "*", element: <Navigate to="/" replace /> },
];

export const router = createBrowserRouter(routes);
