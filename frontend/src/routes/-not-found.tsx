import { Link } from "@tanstack/react-router";
import { Home } from "lucide-react";
import { Button } from "@/components/ui/button";

export function NotFoundComponent() {
  return (
    <div className="max-w-lg pt-16 px-6">
      <h1 className="text-5xl font-bold text-foreground">404</h1>

      <div className="mt-4 space-y-2">
        <h2 className="text-xl font-semibold">Page not found</h2>
        <p className="text-muted-foreground">
          The page you're looking for doesn't exist or has been moved. Check the
          URL or navigate back to the patient search.
        </p>
      </div>

      <div className="mt-6">
        <Button asChild className="active:scale-[0.98] transition-transform">
          <Link to="/">
            <Home className="h-4 w-4 mr-2" />
            Back to Patient Search
          </Link>
        </Button>
      </div>
    </div>
  );
}
