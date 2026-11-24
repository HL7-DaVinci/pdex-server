import { useQuery } from "@tanstack/react-query";
import type { Bundle, Condition, MedicationRequest, Patient } from "fhir/r4";
import { type FhirError, fhirFetch } from "./use-fhir-api";
import { useFhirServer } from "./use-fhir-server";

// A FHIR id cannot hold "=", so this key never matches the key of a Patient read
export function patientCountKey(serverUrl: string) {
  return ["fhir", "Patient", "_summary=count", serverUrl];
}

/**
 * True when the server answers 401 to a small Patient count request. Each
 * other outcome gives false. The query never fails, so its data stays during
 * a refetch.
 */
export function useRequiresAuthorization() {
  const { serverUrl } = useFhirServer();

  return useQuery({
    queryKey: patientCountKey(serverUrl),
    queryFn: () =>
      fhirFetch<Bundle>(`${serverUrl}/Patient?_summary=count`).then(
        () => false,
        (error: FhirError) => error.status === 401,
      ),
    enabled: !!serverUrl,
  });
}

export function usePatient(patientId: string) {
  const { serverUrl } = useFhirServer();

  return useQuery({
    queryKey: ["fhir", "Patient", patientId, serverUrl],
    queryFn: () => fhirFetch<Patient>(`${serverUrl}/Patient/${patientId}`),
    staleTime: 60 * 1000,
    enabled: !!serverUrl && !!patientId,
  });
}

export function useConditions(patientId: string) {
  const { serverUrl } = useFhirServer();

  return useQuery({
    queryKey: ["fhir", "Condition", patientId, serverUrl],
    queryFn: () =>
      fhirFetch<Bundle<Condition>>(
        `${serverUrl}/Condition?patient=${patientId}&_sort=-recorded-date&_count=50`,
      ),
    staleTime: 30 * 1000,
    enabled: !!serverUrl && !!patientId,
  });
}

export function useMedicationRequests(patientId: string) {
  const { serverUrl } = useFhirServer();

  return useQuery({
    queryKey: ["fhir", "MedicationRequest", patientId, serverUrl],
    queryFn: () =>
      fhirFetch<Bundle<MedicationRequest>>(
        `${serverUrl}/MedicationRequest?patient=${patientId}&_sort=-authoredon&_count=50`,
      ),
    staleTime: 30 * 1000,
    enabled: !!serverUrl && !!patientId,
  });
}

export function useConditionCount(patientId: string) {
  const { serverUrl } = useFhirServer();

  return useQuery({
    queryKey: ["fhir", "Condition", "count", patientId, serverUrl],
    queryFn: () =>
      fhirFetch<Bundle>(
        `${serverUrl}/Condition?patient=${patientId}&clinical-status=active&_summary=count`,
      ),
    staleTime: 60 * 1000,
    enabled: !!serverUrl && !!patientId,
  });
}

export function useMedicationRequestCount(patientId: string) {
  const { serverUrl } = useFhirServer();

  return useQuery({
    queryKey: ["fhir", "MedicationRequest", "count", patientId, serverUrl],
    queryFn: () =>
      fhirFetch<Bundle>(
        `${serverUrl}/MedicationRequest?patient=${patientId}&status=active&_summary=count`,
      ),
    staleTime: 60 * 1000,
    enabled: !!serverUrl && !!patientId,
  });
}
