import { Lock, MessagesSquare, Mic, Users } from "lucide-react";
import { useParams } from "react-router";
import { EmptyState, FullPageSpinner, PageHeader } from "@/components/ui/Feedback";
import { useChannels } from "@/features/servers/api";
import { CHANNEL_ICON } from "@/features/servers/channelIcons";
import { useServerContext } from "@/features/servers/useServerContext";
import { can } from "@/lib/permissions";
import { useUi } from "@/stores/ui";

export function ChannelPage() {
  const server = useServerContext();
  const { channelId } = useParams();
  const { data: channels, isPending } = useChannels(server.id);
  const toggleMembers = useUi((s) => s.toggleMemberList);

  if (isPending) return <FullPageSpinner />;
  const channel = channels?.find((c) => c.id === channelId);
  if (!channel) {
    return (
      <div className="flex flex-1 items-center justify-center p-6">
        <EmptyState icon={Lock} title="Kanal nicht verfügbar">
          Der Kanal existiert nicht oder du hast keinen Zugriff.
        </EmptyState>
      </div>
    );
  }

  const Icon = CHANNEL_ICON[channel.type];
  const canSend = can(channel.my_permissions, "SEND_MESSAGES");

  return (
    <>
      <PageHeader
        icon={Icon}
        title={channel.name}
        subtitle={channel.topic}
        actions={
          <button
            onClick={toggleMembers}
            className="rounded-lg p-2 text-muted hover:bg-card hover:text-fg"
            aria-label="Mitgliederliste ein-/ausblenden"
            title="Mitgliederliste"
          >
            <Users className="size-5" />
          </button>
        }
      />
      <div className="flex flex-1 items-center justify-center p-6">
        {channel.type === "voice" ? (
          <EmptyState icon={Mic} title={`Sprachkanal „${channel.name}“`}>
            Beitreten, Stummschalten und Teilnehmeranzeige kommen mit der LiveKit-Integration in
            Meilenstein 3.
            {!can(channel.my_permissions, "CONNECT_VOICE") && (
              <p className="mt-2 text-warning">Du hast in diesem Kanal keine Sprachberechtigung.</p>
            )}
          </EmptyState>
        ) : (
          <EmptyState icon={MessagesSquare} title={`Willkommen in #${channel.name}`}>
            Nachrichten, Antworten, Reaktionen und angeheftete Beiträge kommen in Meilenstein 2.
            <p className="mt-2">
              {canSend ? (
                <span className="text-success">Du darfst hier schreiben.</span>
              ) : (
                <span className="text-warning">Dieser Kanal ist für dich schreibgeschützt.</span>
              )}
            </p>
          </EmptyState>
        )}
      </div>
    </>
  );
}
