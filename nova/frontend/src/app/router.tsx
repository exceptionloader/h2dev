import { Compass, MessageSquare, Users, Zap } from "lucide-react";
import { createBrowserRouter, Navigate } from "react-router";
import { AppShell } from "@/components/layout/AppShell";
import { GuestOnly, RequireAuth } from "@/features/auth/guards";
import { LoginPage } from "@/features/auth/LoginPage";
import { PasswordResetConfirmPage, PasswordResetRequestPage } from "@/features/auth/PasswordResetPages";
import { RegisterPage } from "@/features/auth/RegisterPage";
import { ChannelPage } from "@/features/channels/ChannelPage";
import { HomePage } from "@/features/home/HomePage";
import { ComingSoonPage } from "@/features/roadmap/ComingSoonPage";
import { AuditLogPage } from "@/features/servers/AuditLogPage";
import { ServerLayout } from "@/features/servers/ServerLayout";
import { ServerOverviewPage } from "@/features/servers/ServerOverviewPage";
import { ServerSettingsPage } from "@/features/servers/ServerSettingsPage";
import { SettingsPage } from "@/features/settings/SettingsPage";

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
          {
            path: "/entdecken",
            element: (
              <ComingSoonPage
                title="Entdecken"
                icon={Compass}
                milestone="nach der Beta"
                description="Öffentliche Communities nach Spiel, Sprache und Aktivität finden."
              />
            ),
          },
          {
            path: "/aktivitaeten",
            element: (
              <ComingSoonPage
                title="Aktivitäten"
                icon={Zap}
                milestone="Meilenstein 3"
                description="Instant Gaming Sessions erstellen und beitreten – mit freien Plätzen und Voice-Raum."
              />
            ),
          },
          {
            path: "/freunde",
            element: (
              <ComingSoonPage
                title="Freunde"
                icon={Users}
                milestone="Meilenstein 4"
                description="Freundschaftsanfragen, Online-Status, Blockieren und Einladungen in Aktivitäten."
              />
            ),
          },
          {
            path: "/nachrichten",
            element: (
              <ComingSoonPage
                title="Nachrichten"
                icon={MessageSquare}
                milestone="Meilenstein 4"
                description="Direktnachrichten mit deinen Freunden."
              />
            ),
          },
          { path: "/einstellungen", element: <SettingsPage /> },
          {
            path: "/server/:serverId",
            element: <ServerLayout />,
            children: [
              { index: true, element: <ServerOverviewPage /> },
              { path: "kanal/:channelId", element: <ChannelPage /> },
              { path: "einstellungen", element: <ServerSettingsPage /> },
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
