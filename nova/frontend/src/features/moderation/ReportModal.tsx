import { useState, type FormEvent } from "react";
import { Button } from "@/components/ui/Button";
import { FormError, SelectField, TextAreaField } from "@/components/ui/Field";
import { Modal } from "@/components/ui/Modal";
import { useCreateReport } from "@/features/social/api";
import type { ReportReason } from "@/lib/types";
import { toast } from "@/stores/toasts";

export const REASON_LABEL: Record<ReportReason, string> = {
  spam: "Spam / Werbung",
  harassment: "Belästigung",
  hate: "Hassrede",
  cheating: "Cheating / Betrug",
  nsfw: "Unangemessene Inhalte",
  other: "Sonstiges",
};

export interface ReportTarget {
  label: string;
  message_id?: string;
  target_user_id?: string;
  server_id?: string;
}

export function ReportModal({ target, onClose }: { target: ReportTarget | null; onClose: () => void }) {
  return (
    <Modal open={!!target} onClose={onClose} title="Melden" description={target?.label}>
      {target && <ReportForm target={target} onClose={onClose} />}
    </Modal>
  );
}

function ReportForm({ target, onClose }: { target: ReportTarget; onClose: () => void }) {
  const [reason, setReason] = useState<ReportReason>("spam");
  const [details, setDetails] = useState("");
  const mutation = useCreateReport();
  const submit = (e: FormEvent) => {
    e.preventDefault();
    const { label: _label, ...ids } = target;
    mutation.mutate(
      { reason, details: details.trim() || undefined, ...ids },
      {
        onSuccess: () => {
          toast({ title: "Danke für deine Meldung", body: "Das Moderationsteam schaut sich das an.", tone: "success" });
          onClose();
        },
      },
    );
  };
  return (
    <form onSubmit={submit} className="space-y-4">
      <FormError message={mutation.error?.message} />
      <SelectField label="Grund" value={reason} onChange={(e) => setReason(e.target.value as ReportReason)}>
        {(Object.keys(REASON_LABEL) as ReportReason[]).map((r) => (
          <option key={r} value={r}>
            {REASON_LABEL[r]}
          </option>
        ))}
      </SelectField>
      <TextAreaField label="Details (optional)" value={details} onChange={(e) => setDetails(e.target.value)} maxLength={1000} />
      <div className="flex justify-end gap-2">
        <Button variant="ghost" onClick={onClose}>
          Abbrechen
        </Button>
        <Button type="submit" variant="danger" loading={mutation.isPending}>
          Melden
        </Button>
      </div>
    </form>
  );
}
