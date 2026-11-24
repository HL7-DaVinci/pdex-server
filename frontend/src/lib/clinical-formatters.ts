import type { CodeableConcept, Dosage, HumanName, Identifier } from "fhir/r4";

/**
 * Format a FHIR HumanName array into a display string.
 * Prefers "official" use, falls back to first available name.
 */
export function formatPatientName(names?: HumanName[]): string {
  if (!names?.length) return "Unknown";

  const name =
    names.find((n) => n.use === "official") ??
    names.find((n) => n.use === "usual") ??
    names[0];

  if (name.text) return name.text;

  const parts: string[] = [];
  if (name.family) parts.push(name.family);
  if (name.given?.length) {
    parts.push(name.given.join(" "));
  }

  if (parts.length === 0) return "Unknown";

  // "Family, Given" format if we have both
  if (name.family && name.given?.length) {
    return `${name.family}, ${name.given.join(" ")}`;
  }

  return parts.join(" ");
}

/**
 * Calculate age from a birth date string (YYYY-MM-DD).
 */
export function calculateAge(birthDate?: string): string {
  if (!birthDate) return "";

  const birth = new Date(birthDate);
  const today = new Date();
  let age = today.getFullYear() - birth.getFullYear();
  const monthDiff = today.getMonth() - birth.getMonth();

  if (monthDiff < 0 || (monthDiff === 0 && today.getDate() < birth.getDate())) {
    age--;
  }

  return `${age}y`;
}

/**
 * Format a date string for clinical display (MM/DD/YYYY).
 * Handles FHIR date formats: YYYY, YYYY-MM, YYYY-MM-DD, and full dateTime.
 */
export function formatClinicalDate(dateStr?: string): string {
  if (!dateStr) return "";

  // Handle partial dates
  if (/^\d{4}$/.test(dateStr)) return dateStr;
  if (/^\d{4}-\d{2}$/.test(dateStr)) {
    const [year, month] = dateStr.split("-");
    return `${month}/${year}`;
  }

  const date = new Date(dateStr);
  if (Number.isNaN(date.getTime())) return dateStr;

  return date.toLocaleDateString("en-US", {
    month: "2-digit",
    day: "2-digit",
    year: "numeric",
  });
}

/**
 * Format a CodeableConcept for display.
 * Prefers text, then first coding display, then first coding code.
 */
export function formatCodeableConcept(concept?: CodeableConcept): string {
  if (!concept) return "";
  if (concept.text) return concept.text;
  if (concept.coding?.length) {
    return concept.coding[0].display ?? concept.coding[0].code ?? "";
  }
  return "";
}

/**
 * Format dosage instructions for display.
 */
export function formatDosage(dosage?: Dosage[]): string {
  if (!dosage?.length) return "";

  const first = dosage[0];
  if (first.text) return first.text;

  const parts: string[] = [];

  if (first.doseAndRate?.length) {
    const dose = first.doseAndRate[0];
    if (dose.doseQuantity) {
      parts.push(
        `${dose.doseQuantity.value ?? ""} ${dose.doseQuantity.unit ?? ""}`.trim(),
      );
    }
  }

  if (first.timing?.code?.text) {
    parts.push(first.timing.code.text);
  } else if (first.timing?.repeat?.frequency && first.timing?.repeat?.period) {
    parts.push(
      `${first.timing.repeat.frequency}x per ${first.timing.repeat.period} ${first.timing.repeat.periodUnit ?? ""}`.trim(),
    );
  }

  if (first.route?.text) {
    parts.push(first.route.text);
  }

  return parts.join(", ") || "";
}

/**
 * Get the primary identifier (MRN) from identifiers.
 * Looks for type "MR" (Medical Record Number) first, then falls back to first identifier.
 */
export function getPrimaryIdentifier(
  identifiers?: Identifier[],
): string | undefined {
  if (!identifiers?.length) return undefined;

  const mrn = identifiers.find((id) =>
    id.type?.coding?.some((c) => c.code === "MR"),
  );

  return mrn?.value ?? identifiers[0]?.value;
}
