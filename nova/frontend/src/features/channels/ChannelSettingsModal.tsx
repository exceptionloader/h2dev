import { Trash2 } from "lucide-react";
import { useState, type FormEvent } from "react";
import { useNavigate } from "react-router";
import { Button } from "@/components/ui/Button";
import { FormError, TextField } from "@/components/ui/Field";
import { Modal } from "@/components/ui/Modal";
import { useChannelActions, useOverrides, useRoles } from "@/features/servers/api";
import { cn } from "@/lib/cn";
import type { Channel, Override, Role } from "@/lib/types";
import { toast } from "@/stores/toasts";

const TEXT_PERMS = [
  ["VIEW_CHANNEL", "Sehen"],
  ["SEND_MESSAGES", "Schreiben"],
  ["ATTACH_FILES", "Dateien"],
] as const;
const VOICE_PERMS = [
  ["VIEW_CHANNEL", "Sehen"],
  ["CONNECT_VOICE", "Beitreten"],
  ["SPEAK", "Sprechen"],
] as const;

type Tri = "allow" | "neutral" | "deny";

export function ChannelSettingsModal({ serverId, channel, open, onClose }: { serverId: string; channel: Channel; open: boolean; onClose: () => void }) {
  return (
    <Modal open={open} onClose={onClose} title={`Kanal: ${channel.name}`}>
      {open && <Body serverId={serverId} channel={channel} onClose={onClose} />}
    </Modal>
  );
}

function Body({ serverId, channel, onClose }: { serverId: string; channel: Channel; onClose: () => void }) {
  const [name, setName] = useState(channel.name);
  const [topic, setTopic] = useState(channel.topic ?? "");
  const actions = useChannelActions(serverId);
  const { data: roles = [] } = useRoles(serverId);
  const { data: overrides = [] } = useOverrides(serverId, channel.id, true);
  const navigate = useNavigate();

  const save = (e: FormEvent) => {
    e.preventDefault();
    actions.update.mutate(
      { channelId: channel.id, name: name.trim(), topic: topic.trim() || null },
      { onSuccess: () => toast({ title: "Kanal gespeichert", tone: "success" }) },
    );
  };

  return (
    <div className="max-h-[70vh] space-y-5 overflow-y-auto pr-1">
      <form onSubmit={save} className="space-y-3">
        <FormError message={actions.update.error?.message} />
        <TextField label="Name" value={name} onChange={(e) => setName(e.target.value)} maxLength={64} />
        {channel.type !== "voice" && (
          <TextField label="Thema" value={topic} onChange={(e) => setTopic(e.target.value)} maxLength={300} />
        )}
        <Button type="submit" size="sm" loading={actions.update.isPending}>Speichern</Button>
      </form>

      {channel.type !== "category" && (
        <section>
          <h3 className="mb-1 text-sm font-semibold">Berechtigungen pro Rolle</h3>
          <p className="mb-3 text-xs text-muted">✓ erlaubt · – wie Serverrolle · ✕ verboten</p>
          <FormError message={actions.setOverride.error?.message} />
          <div className="space-y-2">
            {roles.filter((r) => !r.permissions.includes("ADMINISTRATOR")).map((role) => (
              <OverrideRow
                key={role.id}
                role={role}
                override={overrides.find((o) => o.role_id === role.id)}
                perms={channel.type === "voice" ? VOICE_PERMS : TEXT_PERMS}
                onChange={(allow, deny) => actions.setOverride.mutate({ channelId: channel.id, roleId: role.id, allow, deny })}
              />
            ))}
          </div>
        </section>
      )}

      <div className="border-t border-line pt-4">
        <Button
          variant="danger"
          size="sm"
          loading={actions.remove.isPending}
          onClick={() => {
            if (window.confirm(`Kanal „${channel.name}“ mit allen Nachrichten löschen?`))
              actions.remove.mutate(channel.id, {
                onSuccess: () => { onClose(); navigate(`/server/${serverId}`, { replace: true }); },
                onError: (e) => toast({ title: e.message, tone: "error" }),
              });
          }}
        >
          <Trash2 className="size-4" /> Kanal löschen
        </Button>
      </div>
    </div>
  );
}

function OverrideRow({
  role,
  override,
  perms,
  onChange,
}: {
  role: Role;
  override: Override | undefined;
  perms: ReadonlyArray<readonly [string, string]>;
  onChange: (allow: string[], deny: string[]) => void;
}) {
  const allow = override?.allow ?? [];
  const deny = override?.deny ?? [];
  const state = (p: string): Tri => (allow.includes(p) ? "allow" : deny.includes(p) ? "deny" : "neutral");
  const set = (p: string, tri: Tri) => {
    const a = allow.filter((x) => x !== p);
    const d = deny.filter((x) => x !== p);
    if (tri === "allow") a.push(p);
    if (tri === "deny") d.push(p);
    onChange(a, d);
  };
  return (
    <div className="flex items-center gap-3 rounded-lg border border-line bg-base/40 px-3 py-2">
      <span className="flex min-w-0 flex-1 items-center gap-2 truncate text-sm">
        {role.color && <span className="size-2 rounded-full" style={{ backgroundColor: role.color }} />}
        {role.name}
      </span>
      {perms.map(([p, label]) => (
        <div key={p} className="text-center">
          <div className="mb-0.5 text-[10px] text-muted">{label}</div>
          <div className="flex overflow-hidden rounded-md border border-line" role="radiogroup" aria-label={`${role.name}: ${label}`}>
            {(["deny", "neutral", "allow"] as Tri[]).map((t) => (
              <button
                key={t}
                role="radio"
                aria-checked={state(p) === t}
                onClick={() => set(p, t)}
                className={cn(
                  "px-1.5 py-0.5 text-xs",
                  state(p) === t ? (t === "allow" ? "bg-success/30 text-success" : t === "deny" ? "bg-danger/30 text-danger" : "bg-card text-fg") : "text-muted hover:bg-card",
                )}
              >
                {t === "allow" ? "✓" : t === "deny" ? "✕" : "–"}
              </button>
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}
