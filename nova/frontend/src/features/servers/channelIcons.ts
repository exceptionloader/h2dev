import { Folder, Hash, Megaphone, Volume2, type LucideIcon } from "lucide-react";
import type { ChannelType } from "@/lib/types";

export const CHANNEL_ICON: Record<ChannelType, LucideIcon> = {
  text: Hash,
  voice: Volume2,
  announcement: Megaphone,
  category: Folder,
};
