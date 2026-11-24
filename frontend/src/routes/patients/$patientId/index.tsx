import { createFileRoute, useParams } from "@tanstack/react-router";
import { usePatient } from "@/hooks/use-clinical-api";
import {
  calculateAge,
  formatClinicalDate,
  formatPatientName,
  getPrimaryIdentifier,
} from "@/lib/clinical-formatters";

export const Route = createFileRoute("/patients/$patientId/")({
  component: PatientSummary,
});

function PatientSummary() {
  const { patientId } = useParams({ from: "/patients/$patientId/" });
  const { data: patient, isLoading } = usePatient(patientId);

  if (isLoading || !patient) {
    return (
      <div className="p-6 max-w-4xl space-y-3">
        <div className="skeleton h-4 w-32" />
        <div className="grid grid-cols-2 gap-x-6 gap-y-2">
          {Array.from({ length: 6 }).map((_, i) => (
            // biome-ignore lint/suspicious/noArrayIndexKey: skeleton placeholders never reorder
            <div key={i} className="flex justify-between">
              <div className="skeleton h-4 w-20" />
              <div className="skeleton h-4 w-28" />
            </div>
          ))}
        </div>
      </div>
    );
  }

  const address = patient.address?.[0];
  const phone = patient.telecom?.find((t) => t.system === "phone");

  const fields = [
    { label: "Full Name", value: formatPatientName(patient.name) },
    { label: "Date of Birth", value: formatClinicalDate(patient.birthDate) },
    { label: "Age", value: calculateAge(patient.birthDate) },
    {
      label: "Gender",
      value: patient.gender
        ? patient.gender.charAt(0).toUpperCase() + patient.gender.slice(1)
        : undefined,
    },
    { label: "Identifier", value: getPrimaryIdentifier(patient.identifier) },
    ...(address
      ? [
          {
            label: "Address",
            value: [
              address.line?.join(", "),
              address.city,
              address.state,
              address.postalCode,
            ]
              .filter(Boolean)
              .join(", "),
          },
        ]
      : []),
    ...(phone ? [{ label: "Phone", value: phone.value }] : []),
  ].filter((f) => f.value);

  return (
    <div className="p-6 max-w-4xl">
      <section className="border-t pt-4">
        <h2 className="text-base font-semibold mb-3">Demographics</h2>
        <dl className="grid grid-cols-1 md:grid-cols-2 gap-x-6 gap-y-2 text-sm">
          {fields.map((f) => (
            <div key={f.label} className="flex justify-between">
              <dt className="text-muted-foreground">{f.label}</dt>
              <dd className="font-medium text-right">{f.value}</dd>
            </div>
          ))}
        </dl>
      </section>
    </div>
  );
}
