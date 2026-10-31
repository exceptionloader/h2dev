import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, ApiError } from "@/lib/api";
import type { UserMe, UserStatus } from "@/lib/types";

export const meKey = ["me"] as const;

/** Current user, or null when not logged in. */
export function useMe() {
  return useQuery({
    queryKey: meKey,
    queryFn: async () => {
      try {
        return await api<UserMe>("/users/me");
      } catch (err) {
        if (err instanceof ApiError && err.status === 401) return null;
        throw err;
      }
    },
    staleTime: 60_000,
    retry: false,
  });
}

export function useLogin() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { login: string; password: string }) =>
      api<UserMe>("/auth/login", { method: "POST", body }),
    onSuccess: (user) => qc.setQueryData(meKey, user),
  });
}

export function useRegister() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { username: string; email: string; password: string; display_name?: string }) =>
      api<UserMe>("/auth/register", { method: "POST", body }),
    onSuccess: (user) => qc.setQueryData(meKey, user),
  });
}

export function useLogout() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api<void>("/auth/logout", { method: "POST" }),
    onSettled: () => {
      qc.clear();
      qc.setQueryData(meKey, null);
    },
  });
}

export function useUpdateProfile() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { display_name?: string; status?: UserStatus; custom_status?: string }) =>
      api<UserMe>("/users/me", { method: "PATCH", body }),
    onSuccess: (user) => {
      qc.setQueryData(meKey, user);
      qc.invalidateQueries({ queryKey: ["server"] }); // member lists show the profile
    },
  });
}

export function useChangePassword() {
  return useMutation({
    mutationFn: (body: { current_password: string; new_password: string }) =>
      api<void>("/users/me/password", { method: "POST", body }),
  });
}

export function useRequestPasswordReset() {
  return useMutation({
    mutationFn: (email: string) =>
      api<void>("/auth/password-reset/request", { method: "POST", body: { email } }),
  });
}

export function useConfirmPasswordReset() {
  return useMutation({
    mutationFn: (body: { token: string; new_password: string }) =>
      api<void>("/auth/password-reset/confirm", { method: "POST", body }),
  });
}
