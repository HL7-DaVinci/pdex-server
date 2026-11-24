import { render, screen } from "@testing-library/react";
import type { Patient } from "fhir/r4";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { PatientHeader } from "./patient-header";

const patient: Patient = {
  resourceType: "Patient",
  id: "example",
  identifier: [
    { system: "http://example.org/other", value: "OTHER-1" },
    {
      type: { coding: [{ code: "MR" }] },
      system: "http://example.org/mrn",
      value: "MRN-12345",
    },
  ],
  name: [
    { use: "nickname", text: "Janie" },
    { use: "official", family: "Doe", given: ["Jane", "Q"] },
  ],
  gender: "female",
  birthDate: "1990-06-15",
};

describe("PatientHeader", () => {
  beforeEach(() => {
    // calculateAge reads the current date, so pin it for a stable assertion
    vi.setSystemTime(new Date("2024-01-01T12:00:00Z"));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("renders the official name", () => {
    render(<PatientHeader patient={patient} />);

    expect(screen.getByText("Doe, Jane Q")).toBeInTheDocument();
  });

  it("renders demographics from the formatters", () => {
    render(<PatientHeader patient={patient} />);

    // The rendered date format depends on the runtime locale and time zone,
    // so match the label instead of a fixed date string
    expect(screen.getByText(/^DOB: /)).toBeInTheDocument();
    expect(screen.getByText("Age: 33y")).toBeInTheDocument();
    expect(screen.getByText("Female")).toBeInTheDocument();
  });

  it("prefers the medical record number over other identifiers", () => {
    render(<PatientHeader patient={patient} />);

    expect(screen.getByText("MRN-12345")).toBeInTheDocument();
    expect(screen.queryByText("OTHER-1")).not.toBeInTheDocument();
  });

  it("renders counts only when stats are provided", () => {
    const { rerender } = render(<PatientHeader patient={patient} />);

    expect(screen.queryByText(/conditions/)).not.toBeInTheDocument();
    expect(screen.queryByText(/medications/)).not.toBeInTheDocument();

    rerender(
      <PatientHeader
        patient={patient}
        stats={{ conditions: 3, medications: 5 }}
      />,
    );

    expect(screen.getByText(/conditions/)).toBeInTheDocument();
    expect(screen.getByText("3")).toBeInTheDocument();
    expect(screen.getByText(/medications/)).toBeInTheDocument();
    expect(screen.getByText("5")).toBeInTheDocument();
  });

  it("falls back to Unknown when the patient has no usable data", () => {
    render(<PatientHeader patient={{ resourceType: "Patient" }} />);

    expect(screen.getByText("Unknown")).toBeInTheDocument();
    expect(screen.queryByText(/^DOB: /)).not.toBeInTheDocument();
  });
});
