import { Lock, Pin, Settings, Users } from "lucide-react";
import { useState } from "react";
import { useParams } from "react-router";
import { EmptyState, FullPageSpinner, PageHeader } from "@/components/ui/Feedback";
import { Chat } from "@/features/chat/Chat";
import { useChannels } from "@/features/servers/api";
import { CHANNEL_ICON } from "@/features/servers/channelIcons";
import { useServerContext } from "@/features/servers/useServerContext";
import { VoiceChannelView } from "@/features/voice/VoiceChannelView";
import { cn } from "@/lib/cn";
import { can } from "@/lib/permissions";
import { useUi } from "@/stores/ui";
import { ChannelSettingsModal } from "./ChannelSettingsModal";

const headerBtn = "rounded-lg p-2 text-muted hover:bg-card hover:text-fg";

export function ChannelPage() {
  const server = useServerContext();
  const { channelId } = useParams();
  const { data: channels, isPending } = useChannels(server.id);
  const toggleMembers = useUi((s) => s.toggleMemberList);
  const [showPins, setShowPins] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);

  if (isPending) return <FullPageSpinner />;
  const channel = channels?.find((c) => c.id === channelId);
  if (!channel || channel.type === "category") {
    return (
      <div className="flex flex-1 items-center justify-center p-6">
        <EmptyState icon={Lock} title="Kanal nicht verfügbar">
          Der Kanal existiert nicht oder du hast keinen Zugriff.
        </EmptyState>
      </div>
    );
  }
  const Icon = CHANNEL_ICON[channel.type];
  const isText = channel.type === "text" || channel.type === "announcement";

  return (
    <>
      <PageHeader
        icon={Icon}
        title={channel.name}
        subtitle={channel.topic}
        actions={
          <>
            {isText && (
              <button onClick={() => setShowPins((v) => !v)} className={cn(headerBtn, showPins && "text-fg")} aria-pressed={showPins} aria-label="Angeheftete Nachrichten" title="Angeheftete Nachrichten">
                <Pin className="size-5" />
              </button>
            )}
            {can(channel.my_permissions, "MANAGE_CHANNELS") && (
              <button onClick={() => setSettingsOpen(true)} className={headerBtn} aria-label="Kanaleinstellungen" title="Kanaleinstellungen">
                <Settings className="size-5" />
              </button>
            )}
            <button onClick={toggleMembers} className={headerBtn} aria-label="Mitgliederliste ein-/ausblenden" title="Mitgliederliste">
              <Users className="size-5" />
            </button>
          </>
        }
      />
      {isText ? <Chat key={channel.id} channel={channel} showPins={showPins} /> : <VoiceChannelView server={server} channel={channel} />}
      <ChannelSettingsModal serverId={server.id} channel={channel} open={settingsOpen} onClose={() => setSettingsOpen(false)} />
    </>
  );
}
