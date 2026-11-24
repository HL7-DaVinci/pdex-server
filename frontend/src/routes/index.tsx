import { createFileRoute, Link } from "@tanstack/react-router";
import type { Patient } from "fhir/r4";
import { useMemo, useState } from "react";
import { ClinicalTable } from "@/components/clinical-table";
import { PatientSearch } from "@/components/patient-search";
import { TablePagination } from "@/components/table-pagination";
import {
  getPaginationLinks,
  useResourceSearchWithParams,
} from "@/hooks/use-fhir-api";
import { useFhirServer } from "@/hooks/use-fhir-server";
import {
  calculateAge,
  formatClinicalDate,
  formatPatientName,
  getPrimaryIdentifier,
} from "@/lib/clinical-formatters";

export const Route = createFileRoute("/")({
  component: PatientSearchPage,
});

function PatientSearchPage() {
  const { serverUrl } = useFhirServer();

  // A page link belongs to one server, so the key starts the list at page 1
  // with no filter when the server changes
  return <PatientList key={serverUrl} serverUrl={serverUrl} />;
}

function PatientList({ serverUrl }: { serverUrl: string }) {
  const [filters, setFilters] = useState<Record<string, string | undefined>>(
    {},
  );
  const [pageUrl, setPageUrl] = useState<string | undefined>(undefined);
  const [pageSize, setPageSize] = useState(50);

  const params = useMemo(() => {
    const values: Record<string, string> = { _sort: "-_lastUpdated" };
    for (const [name, value] of Object.entries(filters)) {
      if (value) values[name] = value;
    }
    return values;
  }, [filters]);

  const { data, isLoading, isError, error } = useResourceSearchWithParams(
    serverUrl,
    "Patient",
    params,
    pageUrl,
    pageSize,
  );

  const patients: Patient[] =
    data?.entry
      ?.map((e) => e.resource)
      .filter((r): r is Patient => r?.resourceType === "Patient") ?? [];
  const links = getPaginationLinks(data);

  const search = (values: Record<string, string | undefined>) => {
    setFilters(values);
    setPageUrl(undefined);
  };

  return (
    <div className="p-6 space-y-6 max-w-6xl">
      <section>
        <h2 className="text-base font-semibold">Patient Search</h2>
        <p className="text-sm text-muted-foreground mt-0.5 mb-3">
          Search for patients by name, date of birth, or identifier
        </p>
        <PatientSearch onSearch={search} />
      </section>

      {isError && (
        <p className="text-sm text-destructive">
          {error instanceof Error ? error.message : "Failed to search patients"}
        </p>
      )}

      {!isError && (
        <section className="border-t pt-4">
          <h2 className="text-base font-semibold mb-3">
            Results
            {!isLoading && data?.total !== undefined && (
              <span className="ml-2 text-sm font-normal text-muted-foreground">
                (<span className="tabular">{data.total}</span> found)
              </span>
            )}
          </h2>
          <ClinicalTable
            loading={isLoading}
            keyExtractor={(p) => p.id ?? ""}
            columns={[
              {
                header: "Name",
                accessor: (p) => (
                  <Link
                    to="/patients/$patientId"
                    params={{ patientId: p.id ?? "" }}
                    className="font-medium text-primary hover:underline"
                  >
                    {formatPatientName(p.name)}
                  </Link>
                ),
              },
              {
                header: "DOB",
                accessor: (p) => formatClinicalDate(p.birthDate),
              },
              {
                header: "Age",
                accessor: (p) => calculateAge(p.birthDate),
              },
              {
                header: "Gender",
                accessor: (p) => (
                  <span className="capitalize">{p.gender ?? ""}</span>
                ),
              },
              {
                header: "Identifier",
                accessor: (p) => getPrimaryIdentifier(p.identifier) ?? "",
                className: "font-mono text-xs",
              },
              {
                header: "Last Updated",
                accessor: (p) => formatClinicalDate(p.meta?.lastUpdated),
              },
            ]}
            data={patients}
            emptyMessage="No patients found."
          />
          {(isLoading ||
            patients.length > 0 ||
            Boolean(links.previous || links.next)) && (
            <div className="mt-2 border-t">
              <TablePagination
                shown={patients.length}
                total={data?.total}
                pageSize={pageSize}
                onPageSizeChange={(size) => {
                  setPageSize(size);
                  setPageUrl(undefined);
                }}
                previous={links.previous}
                next={links.next}
                onPageChange={setPageUrl}
                disabled={isLoading}
              />
            </div>
          )}
        </section>
      )}
    </div>
  );
}
