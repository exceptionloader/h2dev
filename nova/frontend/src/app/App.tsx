import { MutationCache, QueryCache, QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { meKey } from "@/features/auth/api";
import { RouterProvider } from "react-router";
import { ApiError } from "@/lib/api";
import { router } from "./router";

/** An expired session anywhere logs the user out of the UI (RequireAuth then redirects). */
function onError(err: unknown) {
  if (err instanceof ApiError && err.status === 401) queryClient.setQueryData(meKey, null);
}

export const queryClient: QueryClient = new QueryClient({
  queryCache: new QueryCache({ onError }),
  mutationCache: new MutationCache({ onError }),
  defaultOptions: {
    queries: {
      staleTime: 15_000,
      refetchOnWindowFocus: false,
      // Do not retry client errors such as 401/403/404.
      retry: (count, err) => !(err instanceof ApiError && err.status >= 400 && err.status < 500) && count < 2,
    },
  },
});

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  );
}
