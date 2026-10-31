import { Check, Copy } from "lucide-react";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { FormError, SelectField } from "@/components/ui/Field";
import { Modal } from "@/components/ui/Modal";
import { useCreateInvite } from "@/features/servers/api";
import type { Invite } from "@/lib/types";

export const inviteLink = (code: string) => `${window.location.origin}/einladung/${code}`;

export function InviteModal({ serverId, open, onClose }: { serverId: string; open: boolean; onClose: () => void }) {
  return (
    <Modal open={open} onClose={onClose} title="Leute einladen" description="Teile den Link mit deinen Freunden.">
      {open && <InviteForm serverId={serverId} />}
    </Modal>
  );
}

function InviteForm({ serverId }: { serverId: string }) {
  const [hours, setHours] = useState("24");
  const [uses, setUses] = useState("");
  const [invite, setInvite] = useState<Invite | null>(null);
  const [copied, setCopied] = useState(false);
  const create = useCreateInvite(serverId);

  const generate = () =>
    create.mutate(
      { expires_in_hours: hours ? Number(hours) : null, max_uses: uses ? Number(uses) : null },
      { onSuccess: (inv) => { setInvite(inv); setCopied(false); } },
    );

  return (
    <div className="space-y-4">
      <FormError message={create.error?.message} />
      <div className="grid grid-cols-2 gap-3">
        <SelectField label="Gültig für" value={hours} onChange={(e) => setHours(e.target.value)}>
          <option value="1">1 Stunde</option>
          <option value="24">24 Stunden</option>
          <option value="168">7 Tage</option>
          <option value="720">30 Tage</option>
          <option value="">Unbegrenzt</option>
        </SelectField>
        <SelectField label="Max. Nutzungen" value={uses} onChange={(e) => setUses(e.target.value)}>
          <option value="">Unbegrenzt</option>
          <option value="1">1</option>
          <option value="5">5</option>
          <option value="10">10</option>
          <option value="50">50</option>
        </SelectField>
      </div>
      {invite ? (
        <div className="flex items-center gap-2 rounded-lg border border-line bg-base p-2">
          <code className="flex-1 truncate text-sm">{inviteLink(invite.code)}</code>
          <Button
            size="sm"
            onClick={() => {
              void navigator.clipboard?.writeText(inviteLink(invite.code));
              setCopied(true);
            }}
          >
            {copied ? <Check className="size-4" /> : <Copy className="size-4" />} {copied ? "Kopiert" : "Kopieren"}
          </Button>
        </div>
      ) : null}
      <Button onClick={generate} loading={create.isPending} variant={invite ? "secondary" : "primary"} className="w-full">
        {invite ? "Neuen Link erstellen" : "Einladungslink erstellen"}
      </Button>
    </div>
  );
}
