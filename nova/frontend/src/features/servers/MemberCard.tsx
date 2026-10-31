import { Ban, Crown, Flag, MessageSquare, UserMinus, UserPlus } from "lucide-react";
import { useState } from "react";
import { useNavigate } from "react-router";
import { PRESENCE_LABEL } from "@/components/ui/Avatar";
import { Button } from "@/components/ui/Button";
import { Badge } from "@/components/ui/Feedback";
import { Modal } from "@/components/ui/Modal";
import { UserAvatar } from "@/components/ui/UserAvatar";
import { useMe } from "@/features/auth/api";
import { ReportModal, type ReportTarget } from "@/features/moderation/ReportModal";
import { useFriendActions, useFriends, useOpenDM } from "@/features/social/api";
import { formatDate } from "@/lib/format";
import { can } from "@/lib/permissions";
import type { Member, Role, ServerDetail } from "@/lib/types";
import { usePresence } from "@/stores/realtime";
import { toast } from "@/stores/toasts";
import { useMemberActions } from "./api";

interface Props {
  server: ServerDetail;
  member: Member | null;
  roles: Role[];
  onClose: () => void;
}

export function MemberCard({ server, member, roles, onClose }: Props) {
  return (
    <Modal open={!!member} onClose={onClose} title={member?.user.display_name ?? ""}>
      {member && <MemberCardBody server={server} member={member} roles={roles} onClose={onClose} />}
    </Modal>
  );
}

function MemberCardBody({ server, member, roles, onClose }: Props & { member: Member }) {
  const { data: me } = useMe();
  const { data: friends } = useFriends();
  const friendActions = useFriendActions();
  const openDM = useOpenDM();
  const actions = useMemberActions(server.id);
  const navigate = useNavigate();
  const [report, setReport] = useState<ReportTarget | null>(null);
  const presence = usePresence(member.user);
  const isMe = member.user.id === me?.id;
  const isFriend = friends?.friends.some((f) => f.id === member.user.id);
  const pending = friends?.outgoing.some((r) => r.user.id === member.user.id);
  const memberRoles = roles.filter((r) => member.role_ids.includes(r.id));
  const fail = (err: Error) => toast({ title: err.message, tone: "error" });

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-4">
        <UserAvatar user={member.user} size="lg" />
        <div className="min-w-0">
          <div className="flex items-center gap-2 text-sm text-muted">
            @{member.user.username}
            {member.user.is_bot && <span className="rounded bg-primary/20 px-1.5 text-[10px] font-bold text-primary">BOT</span>}
          </div>
          <div className="text-sm">{member.user.is_bot ? "Bot" : PRESENCE_LABEL[presence]}</div>
          {member.user.custom_status && <div className="text-sm text-muted">„{member.user.custom_status}“</div>}
          <div className="text-xs text-muted">Mitglied seit {formatDate(member.joined_at)}</div>
        </div>
      </div>
      <div className="flex flex-wrap gap-1.5">
        {member.is_owner && <Badge color="#F59E0B"><Crown className="size-3" /> Owner</Badge>}
        {memberRoles.map((r) => (
          <Badge key={r.id} color={r.color}>{r.name}</Badge>
        ))}
      </div>
      {!isMe && !member.user.is_bot && (
        <div className="flex flex-wrap gap-2">
          {isFriend ? (
            <Button
              size="sm"
              onClick={() =>
                openDM.mutate(member.user.id, {
                  onSuccess: (dm) => { onClose(); navigate(`/nachrichten/${dm.id}`); },
                  onError: fail,
                })
              }
            >
              <MessageSquare className="size-4" /> Nachricht
            </Button>
          ) : (
            <Button
              size="sm"
              variant="secondary"
              disabled={pending}
              loading={friendActions.request.isPending}
              onClick={() =>
                friendActions.request.mutate(member.user.username, {
                  onSuccess: () => toast({ title: "Freundschaftsanfrage gesendet", tone: "success" }),
                  onError: fail,
                })
              }
            >
              <UserPlus className="size-4" /> {pending ? "Anfrage gesendet" : "Freund hinzufügen"}
            </Button>
          )}
          <Button size="sm" variant="ghost" onClick={() => setReport({ label: `@${member.user.username}`, target_user_id: member.user.id, server_id: server.id })}>
            <Flag className="size-4" /> Melden
          </Button>
        </div>
      )}
      {!isMe && !member.is_owner && (can(server.my_permissions, "KICK_MEMBERS") || can(server.my_permissions, "BAN_MEMBERS")) && (
        <div className="flex flex-wrap gap-2 border-t border-line pt-4">
          {can(server.my_permissions, "KICK_MEMBERS") && (
            <Button
              size="sm"
              variant="secondary"
              onClick={() => {
                if (window.confirm(`${member.user.display_name} vom Server entfernen?`))
                  actions.kick.mutate(member.user.id, { onSuccess: onClose, onError: fail });
              }}
            >
              <UserMinus className="size-4" /> Entfernen
            </Button>
          )}
          {can(server.my_permissions, "BAN_MEMBERS") && (
            <Button
              size="sm"
              variant="danger"
              onClick={() => {
                const reason = window.prompt(`${member.user.display_name} bannen? Grund (optional):`);
                if (reason !== null)
                  actions.ban.mutate({ user_id: member.user.id, reason: reason || undefined }, { onSuccess: onClose, onError: fail });
              }}
            >
              <Ban className="size-4" /> Bannen
            </Button>
          )}
        </div>
      )}
      <ReportModal target={report} onClose={() => setReport(null)} />
    </div>
  );
}
