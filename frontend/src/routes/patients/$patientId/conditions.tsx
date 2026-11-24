import { createFileRoute, useParams } from "@tanstack/react-router";
import type { Condition } from "fhir/r4";
import { ClinicalTable } from "@/components/clinical-table";
import { Badge } from "@/components/ui/badge";
import { useConditions } from "@/hooks/use-clinical-api";
import {
  formatClinicalDate,
  formatCodeableConcept,
} from "@/lib/clinical-formatters";

export const Route = createFileRoute("/patients/$patientId/conditions")({
  component: ConditionsList,
});

function ConditionsList() {
  const { patientId } = useParams({
    from: "/patients/$patientId/conditions",
  });
  const { data, isLoading, isError, error } = useConditions(patientId);

  const conditions: Condition[] =
    data?.entry?.map((e) => e.resource).filter((r): r is Condition => !!r) ??
    [];

  if (isError) {
    return (
      <div className="p-6">
        <p className="text-sm text-destructive">
          {error instanceof Error ? error.message : "Failed to load conditions"}
        </p>
      </div>
    );
  }

  return (
    <div className="p-6 max-w-6xl">
      <h2 className="text-base font-semibold mb-3">
        Conditions
        {!isLoading && data?.total !== undefined && (
          <span className="ml-2 text-sm font-normal text-muted-foreground">
            (<span className="tabular">{data.total}</span>)
          </span>
        )}
      </h2>
      <ClinicalTable
        loading={isLoading}
        skeletonRows={5}
        keyExtractor={(c) => c.id ?? ""}
        columns={[
          {
            header: "Condition",
            accessor: (c) => formatCodeableConcept(c.code),
          },
          {
            header: "Clinical Status",
            accessor: (c) => {
              const status = formatCodeableConcept(c.clinicalStatus);
              if (!status) return "";
              return (
                <Badge
                  variant={
                    status.toLowerCase() === "active" ? "default" : "secondary"
                  }
                >
                  {status}
                </Badge>
              );
            },
          },
          {
            header: "Verification",
            accessor: (c) => formatCodeableConcept(c.verificationStatus),
          },
          {
            header: "Onset",
            accessor: (c) => formatClinicalDate(c.onsetDateTime) || "",
          },
          {
            header: "Recorded",
            accessor: (c) => formatClinicalDate(c.recordedDate),
          },
        ]}
        data={conditions}
        emptyMessage="No conditions recorded"
      />
    </div>
  );
}
