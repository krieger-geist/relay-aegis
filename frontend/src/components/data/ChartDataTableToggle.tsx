import { useState } from 'react';
import { Table2 } from 'lucide-react';

export function ChartDataTableToggle({ columns, rows, label = 'Show data table' }: { columns: string[]; rows: (string | number)[][]; label?: string }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="chart-table-toggle">
      <button className="btn btn-sm" onClick={() => setOpen((value) => !value)} aria-expanded={open}>
        <Table2 size={12} /> {open ? 'Hide data table' : label}
      </button>
      {open ? <div className="chart-data-table"><table className="app-table"><thead><tr>{columns.map((column) => <th key={column}>{column}</th>)}</tr></thead><tbody>{rows.length ? rows.map((row, index) => <tr key={`${index}-${row.join('-')}`}>{row.map((cell, cellIndex) => <td key={`${index}-${cellIndex}`}>{cell}</td>)}</tr>) : <tr><td colSpan={columns.length}>No data available.</td></tr>}</tbody></table></div> : null}
    </div>
  );
}
