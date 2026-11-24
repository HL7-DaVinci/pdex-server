import type { ReactNode } from "react";

interface Column<T> {
  header: string;
  accessor: (row: T) => ReactNode;
  className?: string;
}

interface ClinicalTableProps<T> {
  columns: Column<T>[];
  data: T[];
  keyExtractor?: (row: T, index: number) => string | number;
  loading?: boolean;
  skeletonRows?: number;
  emptyMessage?: string;
}

export function ClinicalTable<T>({
  columns,
  data,
  keyExtractor,
  loading = false,
  skeletonRows = 5,
  emptyMessage = "No data available",
}: ClinicalTableProps<T>) {
  if (!loading && data.length === 0) {
    return <p className="py-8 text-sm text-muted-foreground">{emptyMessage}</p>;
  }

  const skeletonWidths = ["w-3/4", "w-1/2", "w-2/3", "w-1/3", "w-5/6"];

  return (
    <div className="overflow-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b bg-muted/50">
            {columns.map((col) => (
              <th
                key={col.header}
                className="h-9 px-3 text-left text-xs font-medium text-muted-foreground"
              >
                {col.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-border">
          {loading
            ? Array.from({ length: skeletonRows }).map((_, i) => (
                // biome-ignore lint/suspicious/noArrayIndexKey: skeleton placeholders never reorder
                <tr key={i}>
                  {columns.map((col, j) => (
                    <td key={col.header} className="px-3 py-2">
                      <div
                        className={`skeleton h-4 ${skeletonWidths[j % skeletonWidths.length]}`}
                      />
                    </td>
                  ))}
                </tr>
              ))
            : data.map((row, i) => (
                <tr
                  key={keyExtractor ? keyExtractor(row, i) : i}
                  className="stagger-item transition-colors hover:[box-shadow:inset_2px_0_0_var(--primary)]"
                  style={{ animationDelay: `${i * 30}ms` }}
                >
                  {columns.map((col) => (
                    <td
                      key={col.header}
                      className={`px-3 py-2 ${col.className ?? ""}`}
                    >
                      {col.accessor(row)}
                    </td>
                  ))}
                </tr>
              ))}
        </tbody>
      </table>
    </div>
  );
}
