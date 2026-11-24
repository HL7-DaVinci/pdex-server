import {
  createFileRoute,
  Link,
  Outlet,
  useParams,
} from "@tanstack/react-router";
import { Home } from "lucide-react";
import { PatientHeader } from "@/components/patient-header";
import { Button } from "@/components/ui/button";
import {
  useConditionCount,
  useMedicationRequestCount,
  usePatient,
} from "@/hooks/use-clinical-api";
import { cn } from "@/lib/utils";

export const Route = createFileRoute("/patients/$patientId")({
  component: PatientLayout,
});

const tabs = [
  { label: "Summary", to: "/patients/$patientId" },
  { label: "Conditions", to: "/patients/$patientId/conditions" },
  { label: "Medications", to: "/patients/$patientId/medications" },
] as const;

function PatientLayout() {
  const { patientId } = useParams({ from: "/patients/$patientId" });
  const { data: patient, isLoading, isError, error } = usePatient(patientId);
  const { data: conditionCount } = useConditionCount(patientId);
  const { data: medCount } = useMedicationRequestCount(patientId);

  if (isLoading) {
    return (
      <div className="p-4 bg-card border-b">
        <div className="skeleton h-6 w-48 mb-2" />
        <div className="flex gap-3">
          <div className="skeleton h-4 w-24" />
          <div className="skeleton h-4 w-20" />
          <div className="skeleton h-4 w-16" />
        </div>
      </div>
    );
  }

  // FHIR servers answer 404 for an unknown id and 410 for a deleted one
  if (error?.status === 404 || error?.status === 410) {
    return (
      <div className="max-w-lg pt-16 px-6">
        <h1 className="text-xl font-semibold">Patient not found</h1>
        <p className="mt-2 text-muted-foreground">
          No patient with ID{" "}
          <span className="font-mono text-foreground">{patientId}</span> exists
          on this FHIR server.
        </p>
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

  if (isError || !patient) {
    return (
      <div className="p-6">
        <p className="text-sm text-destructive">
          {error instanceof Error ? error.message : "Failed to load patient"}
        </p>
      </div>
    );
  }

  return (
    <div>
      <PatientHeader
        patient={patient}
        stats={{
          conditions: conditionCount?.total,
          medications: medCount?.total,
        }}
      />

      <div className="border-b bg-background">
        <nav className="flex gap-0 px-4" aria-label="Patient tabs">
          {tabs.map((tab) => (
            <Link
              key={tab.to}
              to={tab.to}
              params={{ patientId }}
              className={cn(
                "px-3 py-2 text-sm font-medium border-b-2 -mb-px transition-colors",
                "text-muted-foreground hover:text-foreground border-transparent hover:border-border",
              )}
              activeProps={{
                className:
                  "text-primary border-primary hover:text-primary hover:border-primary",
              }}
              activeOptions={{
                exact: tab.to === "/patients/$patientId",
              }}
            >
              {tab.label}
            </Link>
          ))}
        </nav>
      </div>

      <Outlet />
    </div>
  );
}
