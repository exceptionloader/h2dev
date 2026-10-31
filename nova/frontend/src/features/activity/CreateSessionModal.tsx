import { useState, type FormEvent } from "react";
import { useNavigate } from "react-router";
import { Button } from "@/components/ui/Button";
import { FormError, SelectField, TextAreaField, TextField } from "@/components/ui/Field";
import { Modal } from "@/components/ui/Modal";
import { useChannels, useServers } from "@/features/servers/api";
import { useCreateSession } from "./api";

export function CreateSessionModal({ open, onClose, serverId }: { open: boolean; onClose: () => void; serverId?: string }) {
  return (
    <Modal open={open} onClose={onClose} title="Session starten" description="Sag Bescheid, was du spielen willst – alle auf dem Server sehen es sofort.">
      {open && <Form onClose={onClose} fixedServerId={serverId} />}
    </Modal>
  );
}

function toLocalInput(d: Date) {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function Form({ onClose, fixedServerId }: { onClose: () => void; fixedServerId?: string }) {
  const { data: servers = [] } = useServers();
  const [serverId, setServerId] = useState(fixedServerId ?? "");
  const effectiveServer = serverId || servers[0]?.id || "";
  const { data: channels = [] } = useChannels(effectiveServer);
  const [game, setGame] = useState("");
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [max, setMax] = useState(5);
  const [when, setWhen] = useState<"now" | "later">("now");
  const [start, setStart] = useState(toLocalInput(new Date(Date.now() + 3600_000)));
  const [voice, setVoice] = useState("");
  const create = useCreateSession();
  const navigate = useNavigate();
  const voiceChannels = channels.filter((c) => c.type === "voice" && c.my_permissions.includes("CONNECT_VOICE"));

  const submit = (e: FormEvent) => {
    e.preventDefault();
    create.mutate(
      {
        serverId: effectiveServer,
        game: game.trim(),
        title: title.trim(),
        description: description.trim() || undefined,
        max_participants: max,
        start_time: when === "later" ? new Date(start).toISOString() : undefined,
        voice_channel_id: voice || null,
      },
      { onSuccess: (s) => { onClose(); navigate(`/sessions/${s.id}`); } },
    );
  };

  return (
    <form onSubmit={submit} className="space-y-3">
      <FormError message={create.error?.message} />
      {!fixedServerId && (
        <SelectField label="Server" value={effectiveServer} onChange={(e) => { setServerId(e.target.value); setVoice(""); }}>
          {servers.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
        </SelectField>
      )}
      <div className="grid grid-cols-2 gap-3">
        <TextField label="Spiel" value={game} onChange={(e) => setGame(e.target.value)} maxLength={64} placeholder="Valorant" required autoFocus />
        <TextField label="Plätze" type="number" min={2} max={100} value={max} onChange={(e) => setMax(Number(e.target.value))} />
      </div>
      <TextField label="Titel" value={title} onChange={(e) => setTitle(e.target.value)} maxLength={100} placeholder="Ranked – 2 Spieler gesucht" required />
      <TextAreaField label="Beschreibung" value={description} onChange={(e) => setDescription(e.target.value)} maxLength={500} placeholder="Rang, Rollen, Stimmung …" />
      <div className="grid grid-cols-2 gap-3">
        <SelectField label="Start" value={when} onChange={(e) => setWhen(e.target.value as "now" | "later")}>
          <option value="now">Jetzt</option>
          <option value="later">Später</option>
        </SelectField>
        {when === "later" && <TextField label="Zeitpunkt" type="datetime-local" value={start} onChange={(e) => setStart(e.target.value)} />}
      </div>
      <SelectField label="Sprachkanal" value={voice} onChange={(e) => setVoice(e.target.value)}>
        <option value="">Keiner</option>
        {voiceChannels.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
      </SelectField>
      <div className="flex justify-end gap-2 pt-1">
        <Button variant="ghost" onClick={onClose}>Abbrechen</Button>
        <Button type="submit" loading={create.isPending} disabled={!game.trim() || !title.trim() || !effectiveServer}>Session starten</Button>
      </div>
    </form>
  );
}
