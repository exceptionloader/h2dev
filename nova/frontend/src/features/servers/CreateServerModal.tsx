import { useState, type FormEvent } from "react";
import { useNavigate } from "react-router";
import { Button } from "@/components/ui/Button";
import { FormError, SelectField, TextAreaField, TextField } from "@/components/ui/Field";
import { Modal } from "@/components/ui/Modal";
import type { Visibility } from "@/lib/types";
import { useUi } from "@/stores/ui";
import { useCreateServer } from "./api";

export function CreateServerModal() {
  const open = useUi((s) => s.createServerOpen);
  const setOpen = useUi((s) => s.setCreateServerOpen);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [visibility, setVisibility] = useState<Visibility>("private");
  const mutation = useCreateServer();
  const navigate = useNavigate();

  const close = () => {
    setOpen(false);
    setName("");
    setDescription("");
    setVisibility("private");
    mutation.reset();
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    mutation.mutate(
      { name: name.trim(), description: description.trim() || undefined, visibility },
      {
        onSuccess: (server) => {
          close();
          navigate(`/server/${server.id}`);
        },
      },
    );
  };

  return (
    <Modal
      open={open}
      onClose={close}
      title="Server erstellen"
      description="Du wirst automatisch Owner und kannst Rollen und Kanäle verwalten."
    >
      <form onSubmit={submit} className="space-y-4">
        <FormError message={mutation.error?.message} />
        <TextField
          label="Servername"
          value={name}
          onChange={(e) => setName(e.target.value)}
          maxLength={64}
          placeholder="z. B. Valorant Crew"
          required
          autoFocus
        />
        <TextAreaField
          label="Beschreibung"
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          maxLength={500}
          placeholder="Worum geht's auf deinem Server?"
        />
        <SelectField
          label="Sichtbarkeit"
          value={visibility}
          onChange={(e) => setVisibility(e.target.value as Visibility)}
          hint="Öffentliche Server werden später in „Entdecken“ auffindbar."
        >
          <option value="private">Privat – nur mit Einladung</option>
          <option value="public">Öffentlich</option>
        </SelectField>
        <div className="flex justify-end gap-2 pt-2">
          <Button variant="ghost" onClick={close}>
            Abbrechen
          </Button>
          <Button type="submit" loading={mutation.isPending} disabled={!name.trim()}>
            Erstellen
          </Button>
        </div>
      </form>
    </Modal>
  );
}
