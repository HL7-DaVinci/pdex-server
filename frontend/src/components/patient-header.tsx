import type { Patient } from "fhir/r4";
import {
  calculateAge,
  formatClinicalDate,
  formatPatientName,
  getPrimaryIdentifier,
} from "@/lib/clinical-formatters";

interface PatientHeaderProps {
  patient: Patient;
  stats?: { conditions?: number; medications?: number };
}

export function PatientHeader({ patient, stats }: PatientHeaderProps) {
  const name = formatPatientName(patient.name);
  const dob = formatClinicalDate(patient.birthDate);
  const age = calculateAge(patient.birthDate);
  const mrn = getPrimaryIdentifier(patient.identifier);

  const meta = [
    dob && `DOB: ${dob}`,
    age && `Age: ${age}`,
    patient.gender &&
      patient.gender.charAt(0).toUpperCase() + patient.gender.slice(1),
    mrn && (
      <span key="mrn">
        MRN: <span className="tabular">{mrn}</span>
      </span>
    ),
    stats?.conditions !== undefined && (
      <span key="conditions">
        <span className="tabular">{stats.conditions}</span> conditions
      </span>
    ),
    stats?.medications !== undefined && (
      <span key="medications">
        <span className="tabular">{stats.medications}</span> medications
      </span>
    ),
  ].filter(Boolean);

  return (
    <div className="p-4 bg-card border-b">
      <h1 className="text-lg font-semibold truncate">{name}</h1>
      <div className="flex flex-wrap items-center gap-y-1 text-sm text-muted-foreground mt-0.5">
        {meta.map((item, i) => (
          <span
            key={typeof item === "string" ? item : i}
            className="flex items-center"
          >
            {i > 0 && <span className="text-border mx-2">|</span>}
            {item}
          </span>
        ))}
      </div>
    </div>
  );
}
