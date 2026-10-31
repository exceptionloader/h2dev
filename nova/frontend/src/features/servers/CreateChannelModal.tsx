import { useState, type FormEvent } from "react";
import { useNavigate } from "react-router";
import { Button } from "@/components/ui/Button";
import { FormError, SelectField, TextField } from "@/components/ui/Field";
import { Modal } from "@/components/ui/Modal";
import type { Channel, ChannelType } from "@/lib/types";
import { useCreateChannel } from "./api";

export const CHANNEL_TYPE_LABEL: Record<ChannelType, string> = {
  text: "Textkanal",
  voice: "Sprachkanal",
  announcement: "Ankündigungen",
  category: "Kategorie",
};

interface Props {
  serverId: string;
  categories: Channel[];
  defaultParentId: string | null;
  open: boolean;
  onClose: () => void;
}

export function CreateChannelModal({ serverId, categories, defaultParentId, open, onClose }: Props) {
  return (
    <Modal open={open} onClose={onClose} title="Kanal erstellen">
      {/* Re-mount the form on every open so state resets cleanly. */}
      {open && (
        <ChannelForm serverId={serverId} categories={categories} defaultParentId={defaultParentId} onClose={onClose} />
      )}
    </Modal>
  );
}

function ChannelForm({ serverId, categories, defaultParentId, onClose }: Omit<Props, "open">) {
  const [name, setName] = useState("");
  const [type, setType] = useState<ChannelType>("text");
  const [parentId, setParentId] = useState(defaultParentId ?? "");
  const [isPrivate, setIsPrivate] = useState(false);
  const mutation = useCreateChannel(serverId);
  const navigate = useNavigate();

  const submit = (e: FormEvent) => {
    e.preventDefault();
    mutation.mutate(
      {
        name: name.trim(),
        type,
        parent_id: type === "category" ? null : parentId || null,
        private: isPrivate,
      },
      {
        onSuccess: (channel) => {
          onClose();
          if (channel.type !== "category") navigate(`/server/${serverId}/kanal/${channel.id}`);
        },
      },
    );
  };

  return (
    <form onSubmit={submit} className="space-y-4">
      <FormError message={mutation.error?.message} />
      <SelectField label="Typ" value={type} onChange={(e) => setType(e.target.value as ChannelType)}>
        {(Object.keys(CHANNEL_TYPE_LABEL) as ChannelType[]).map((t) => (
          <option key={t} value={t}>
            {CHANNEL_TYPE_LABEL[t]}
          </option>
        ))}
      </SelectField>
      <TextField
        label="Name"
        value={name}
        onChange={(e) => setName(e.target.value)}
        maxLength={64}
        placeholder={type === "voice" ? "z. B. Ranked" : "z. B. clips"}
        required
        autoFocus
      />
      {type !== "category" && (
        <SelectField label="Kategorie" value={parentId} onChange={(e) => setParentId(e.target.value)}>
          <option value="">Keine Kategorie</option>
          {categories.map((c) => (
            <option key={c.id} value={c.id}>
              {c.name}
            </option>
          ))}
        </SelectField>
      )}
      <label className="flex cursor-pointer items-start gap-3 rounded-lg border border-line p-3 hover:border-line-strong">
        <input
          type="checkbox"
          className="mt-0.5 size-4 accent-primary"
          checked={isPrivate}
          onChange={(e) => setIsPrivate(e.target.checked)}
        />
        <span>
          <span className="block text-sm font-medium">Privater Kanal</span>
          <span className="block text-xs text-muted">
            Nur Admins, der Owner und Rollen mit Freigabe sehen diesen Kanal.
          </span>
        </span>
      </label>
      <div className="flex justify-end gap-2 pt-2">
        <Button variant="ghost" onClick={onClose}>
          Abbrechen
        </Button>
        <Button type="submit" loading={mutation.isPending} disabled={!name.trim()}>
          Erstellen
        </Button>
      </div>
    </form>
  );
}
