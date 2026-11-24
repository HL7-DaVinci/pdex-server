import { Plus, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  type CustomHeader,
  DISALLOWED_HEADER_NAMES,
  hasInvalidHeaderValueChars,
  isValidHeaderName,
} from "@/lib/fhir-config";

export function headerEditorError(headers: CustomHeader[]): string | undefined {
  const seen = new Set<string>();
  for (const header of headers) {
    const name = header.name.trim();
    const lower = name.toLowerCase();
    if (!lower) {
      if (header.value.trim().length > 0) {
        return "Header value needs a name";
      }
      continue;
    }
    if (DISALLOWED_HEADER_NAMES.has(lower)) {
      return `Header "${header.name}" cannot be sent by the browser`;
    }
    if (!isValidHeaderName(name)) {
      return `Header "${header.name}" is not a valid header name`;
    }
    if (hasInvalidHeaderValueChars(header.value)) {
      return `Header "${header.name}" has an invalid value`;
    }
    if (seen.has(lower)) {
      return `Duplicate header "${header.name}"`;
    }
    seen.add(lower);
  }
  return undefined;
}

/** The rows worth saving, rows without a name are still being typed. */
export function validCustomHeaders(headers: CustomHeader[]): CustomHeader[] {
  return headers
    .filter((header) => header.name.trim().length > 0)
    .map((header) => ({ name: header.name.trim(), value: header.value }));
}

interface HeaderEditorProps {
  headers: CustomHeader[];
  onChange: (headers: CustomHeader[]) => void;
}

export function HeaderEditor({ headers, onChange }: HeaderEditorProps) {
  const error = headerEditorError(headers);

  const updateRow = (index: number, patch: Partial<CustomHeader>) => {
    onChange(
      headers.map((header, i) =>
        i === index ? { ...header, ...patch } : header,
      ),
    );
  };

  return (
    <div className="space-y-2">
      <Label>Custom Headers</Label>
      {headers.map((header, index) => (
        // biome-ignore lint/suspicious/noArrayIndexKey: rows have no stable id and their inputs are controlled
        <div key={index} className="flex gap-2">
          <Input
            aria-label={`Header ${index + 1} name`}
            placeholder="X-Api-Key"
            value={header.name}
            onChange={(e) => updateRow(index, { name: e.target.value })}
            className="h-8 text-xs flex-1"
          />
          <Input
            aria-label={`Header ${index + 1} value`}
            placeholder="value"
            value={header.value}
            onChange={(e) => updateRow(index, { value: e.target.value })}
            className="h-8 text-xs flex-1"
          />
          <Button
            variant="ghost"
            size="sm"
            aria-label={`Remove header ${index + 1}`}
            onClick={() => onChange(headers.filter((_, i) => i !== index))}
          >
            <Trash2 className="h-3.5 w-3.5" />
          </Button>
        </div>
      ))}
      <Button
        variant="outline"
        size="sm"
        onClick={() => onChange([...headers, { name: "", value: "" }])}
      >
        <Plus className="h-3.5 w-3.5" />
        Add header
      </Button>
      {error && <p className="text-xs text-destructive">{error}</p>}
    </div>
  );
}
