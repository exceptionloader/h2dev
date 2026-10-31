import { create } from "zustand";

interface UiState {
  createServerOpen: boolean;
  memberListOpen: boolean;
  setCreateServerOpen: (open: boolean) => void;
  toggleMemberList: () => void;
}

export const useUi = create<UiState>((set) => ({
  createServerOpen: false,
  memberListOpen: true,
  setCreateServerOpen: (open) => set({ createServerOpen: open }),
  toggleMemberList: () => set((s) => ({ memberListOpen: !s.memberListOpen })),
}));
