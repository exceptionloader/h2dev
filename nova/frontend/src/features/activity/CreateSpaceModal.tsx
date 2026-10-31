import { useState, type FormEvent } from "react";
import { useNavigate } from "react-router";
import { Button } from "@/components/ui/Button";
import { FormError, TextField } from "@/components/ui/Field";
import { Modal } from "@/components/ui/Modal";
import { useSpaceActions } from "./api";

export function CreateSpaceModal({ serverId, open, onClose }: { serverId: string; open: boolean; onClose: () => void }) {
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const { create } = useSpaceActions(serverId);
  const navigate = useNavigate();
  const close = () => {
    setName("");
    setDescription("");
    create.reset();
    onClose();
  };
  const submit = (e: FormEvent) => {
    e.preventDefault();
    create.mutate(
      { name: name.trim(), description: description.trim() || undefined },
      { onSuccess: (space) => { close(); navigate(`/server/${serverId}/space/${space.id}`); } },
    );
  };
  return (
    <Modal open={open} onClose={close} title="Gaming Space erstellen" description="Eine Karte mit Voice-Zonen: Lobby, Gaming, Chill, Strategie und AFK. Jede Zone ist ein Sprachkanal.">
      <form onSubmit={submit} className="space-y-4">
        <FormError message={create.error?.message} />
        <TextField label="Name" value={name} onChange={(e) => setName(e.target.value)} maxLength={64} placeholder="z. B. Arena" required autoFocus />
        <TextField label="Beschreibung" value={description} onChange={(e) => setDescription(e.target.value)} maxLength={300} placeholder="Unser Treffpunkt für Game Nights" />
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={close}>Abbrechen</Button>
          <Button type="submit" loading={create.isPending} disabled={!name.trim()}>Erstellen</Button>
        </div>
      </form>
    </Modal>
  );
}
