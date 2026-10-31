import { Headphones, HeadphoneOff, Loader2, Mic, MicOff, PhoneOff, Wifi, WifiOff } from "lucide-react";
import { Link } from "react-router";
import { cn } from "@/lib/cn";
import { useVoice } from "./voiceStore";

const STATUS_TEXT = {
  idle: "Nicht verbunden",
  connecting: "Verbinde …",
  connected: "Verbunden",
  reconnecting: "Verbindung wird wiederhergestellt …",
  error: "Fehler",
} as const;

export function VoiceBar() {
  const v = useVoice();
  const active = v.status !== "idle" && v.target;

  if (!active) {
    return (
      <div className="ml-auto flex items-center gap-3 text-sm text-muted">
        {v.error && <span className="text-xs text-danger">{v.error}</span>}
        <span className="inline-flex items-center gap-1.5 rounded-full border border-line px-2.5 py-0.5 text-xs">
          <WifiOff className="size-3.5" aria-hidden /> Sprache: nicht verbunden
        </span>
      </div>
    );
  }

  const btn = "rounded-lg p-2 transition-colors hover:bg-card disabled:opacity-40";
  return (
    <div className="ml-auto flex items-center gap-3" aria-label="Sprachverbindung">
      <div className="min-w-0 text-right leading-tight">
        <div className={cn("flex items-center justify-end gap-1.5 text-xs font-semibold", v.status === "connected" ? "text-success" : v.status === "error" ? "text-danger" : "text-warning")} aria-live="polite">
          {v.status === "connecting" || v.status === "reconnecting" ? <Loader2 className="size-3.5 animate-spin" /> : <Wifi className="size-3.5" />}
          {STATUS_TEXT[v.status]}
        </div>
        <Link to={`/server/${v.target!.serverId}/kanal/${v.target!.channelId}`} className="block truncate text-xs text-muted hover:text-fg">
          {v.target!.channelName} · {v.target!.serverName}
        </Link>
        {v.error && <div className="text-[11px] text-danger">{v.error}</div>}
      </div>
      <button
        className={cn(btn, v.muted ? "text-danger" : "text-fg")}
        onClick={() => void v.toggleMute()}
        disabled={v.status !== "connected" || !v.canSpeak}
        aria-pressed={v.muted}
        aria-label={v.muted ? "Mikrofon einschalten" : "Stummschalten"}
        title={v.canSpeak ? (v.muted ? "Mikrofon einschalten" : "Stummschalten") : "Keine Sprechberechtigung"}
      >
        {v.muted ? <MicOff className="size-5" /> : <Mic className="size-5" />}
      </button>
      <button
        className={cn(btn, v.deafened ? "text-danger" : "text-fg")}
        onClick={() => void v.toggleDeafen()}
        disabled={v.status !== "connected"}
        aria-pressed={v.deafened}
        aria-label={v.deafened ? "Ton einschalten" : "Taub schalten"}
        title={v.deafened ? "Ton einschalten" : "Taub schalten"}
      >
        {v.deafened ? <HeadphoneOff className="size-5" /> : <Headphones className="size-5" />}
      </button>
      <button className={cn(btn, "text-danger hover:bg-danger/15")} onClick={() => void v.leave()} aria-label="Verbindung trennen" title="Trennen">
        <PhoneOff className="size-5" />
      </button>
    </div>
  );
}
