import { Outlet } from "react-router";
import { CreateServerModal } from "@/features/servers/CreateServerModal";
import { MainNav } from "./MainNav";
import { StatusBar } from "./StatusBar";

export function AppShell() {
  return (
    <div className="flex h-full flex-col">
      <div className="flex min-h-0 flex-1">
        <MainNav />
        <main className="flex min-w-0 flex-1">
          <Outlet />
        </main>
      </div>
      <StatusBar />
      <CreateServerModal />
    </div>
  );
}
