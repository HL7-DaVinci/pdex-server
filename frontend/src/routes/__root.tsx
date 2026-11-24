import { TanStackDevtools } from "@tanstack/react-devtools";
import { createRootRoute, Link, Outlet } from "@tanstack/react-router";
import { TanStackRouterDevtoolsPanel } from "@tanstack/react-router-devtools";
import { Settings, Users } from "lucide-react";
import { useState } from "react";
import { ServerStatus } from "@/components/server-status";
import { SettingsDialog } from "@/components/settings-dialog";
import { SignedInUser, SignInGate } from "@/components/sign-in-gate";
import { ThemeToggle } from "@/components/theme-toggle";
import { Button } from "@/components/ui/button";
import { TooltipProvider } from "@/components/ui/tooltip";
import { NotFoundComponent } from "./-not-found";

const navItems = [{ label: "Patients", to: "/", icon: Users }] as const;

export const Route = createRootRoute({
  component: RootComponent,
  notFoundComponent: NotFoundComponent,
});

function RootComponent() {
  const [settingsOpen, setSettingsOpen] = useState(false);

  return (
    <TooltipProvider>
      <div className="flex min-h-screen flex-col">
        <header className="flex h-12 shrink-0 items-center justify-between gap-4 border-b px-4 bg-background/80 backdrop-blur-sm">
          <div className="flex items-center gap-4">
            <div className="flex items-center gap-2">
              <div className="flex h-8 w-8 items-center justify-center rounded-md border border-border bg-background">
                <span className="text-sm font-semibold tracking-tight">PD</span>
              </div>
              <span className="text-sm font-semibold">Da Vinci PDex</span>
            </div>

            <nav className="flex items-center gap-1">
              {navItems.map((item) => (
                <Link
                  key={item.to}
                  to={item.to}
                  className="flex items-center gap-1.5 rounded-md px-3 py-1.5 text-sm text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
                  activeProps={{
                    className:
                      "flex items-center gap-1.5 rounded-md px-3 py-1.5 text-sm bg-accent text-foreground font-medium",
                  }}
                  activeOptions={{ exact: item.to === "/" }}
                >
                  <item.icon className="h-4 w-4" />
                  <span>{item.label}</span>
                </Link>
              ))}
            </nav>
          </div>

          <div className="flex items-center gap-3">
            <SignedInUser />
            <ServerStatus showLatency />
            <ThemeToggle />

            <Button
              variant="ghost"
              size="icon"
              className="h-8 w-8"
              onClick={() => setSettingsOpen(true)}
              aria-label="Open settings"
            >
              <Settings className="h-4 w-4" />
            </Button>
          </div>
        </header>

        <main className="flex-1 overflow-auto bg-background">
          <SignInGate onOpenSettings={() => setSettingsOpen(true)}>
            <Outlet />
          </SignInGate>
        </main>
      </div>

      <SettingsDialog open={settingsOpen} onOpenChange={setSettingsOpen} />

      <TanStackDevtools
        config={{
          position: "bottom-right",
        }}
        plugins={[
          {
            name: "Tanstack Router",
            render: <TanStackRouterDevtoolsPanel />,
          },
        ]}
      />
    </TooltipProvider>
  );
}
