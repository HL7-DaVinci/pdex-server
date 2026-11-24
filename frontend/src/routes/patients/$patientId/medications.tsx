import { createFileRoute, useParams } from "@tanstack/react-router";
import type { MedicationRequest } from "fhir/r4";
import { ClinicalTable } from "@/components/clinical-table";
import { Badge } from "@/components/ui/badge";
import { useMedicationRequests } from "@/hooks/use-clinical-api";
import {
  formatClinicalDate,
  formatCodeableConcept,
  formatDosage,
} from "@/lib/clinical-formatters";

export const Route = createFileRoute("/patients/$patientId/medications")({
  component: MedicationsList,
});

function MedicationsList() {
  const { patientId } = useParams({
    from: "/patients/$patientId/medications",
  });
  const { data, isLoading, isError, error } = useMedicationRequests(patientId);

  const medications: MedicationRequest[] =
    data?.entry
      ?.map((e) => e.resource)
      .filter((r): r is MedicationRequest => !!r) ?? [];

  if (isError) {
    return (
      <div className="p-6">
        <p className="text-sm text-destructive">
          {error instanceof Error
            ? error.message
            : "Failed to load medications"}
        </p>
      </div>
    );
  }

  return (
    <div className="p-6 max-w-6xl">
      <h2 className="text-base font-semibold mb-3">
        Medications
        {!isLoading && data?.total !== undefined && (
          <span className="ml-2 text-sm font-normal text-muted-foreground">
            (<span className="tabular">{data.total}</span>)
          </span>
        )}
      </h2>
      <ClinicalTable
        loading={isLoading}
        skeletonRows={5}
        keyExtractor={(m) => m.id ?? ""}
        columns={[
          {
            header: "Medication",
            accessor: (m) => formatCodeableConcept(m.medicationCodeableConcept),
          },
          {
            header: "Status",
            accessor: (m) => {
              if (!m.status) return "";
              const isActive = m.status === "active";
              return (
                <Badge variant={isActive ? "default" : "secondary"}>
                  {m.status}
                </Badge>
              );
            },
          },
          {
            header: "Authored",
            accessor: (m) => formatClinicalDate(m.authoredOn),
          },
          {
            header: "Dosage",
            accessor: (m) => formatDosage(m.dosageInstruction),
          },
        ]}
        data={medications}
        emptyMessage="No medications recorded"
      />
    </div>
  );
}
