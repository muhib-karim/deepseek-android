"""data-analysis skill: CSV statistics and charts for DeepSeek Android agent mode.

Runs on the phone through run_python's interpreter, in the chat's folder. Uses only the standard
library for statistics and matplotlib (Agg) for charts.
"""
import csv
import math
import os
import statistics

MISSING = {"", "na", "n/a", "nan", "null", "none", "-"}


def _read(path, limit=200000):
    if os.path.isabs(path) or ".." in path.replace("\\", "/").split("/"):
        raise ValueError("Use a CSV file inside this chat's folder")
    with open(path, newline="", encoding="utf-8-sig") as handle:
        sample = handle.read(4096)
        handle.seek(0)
        try:
            dialect = csv.Sniffer().sniff(sample, delimiters=",;\t|")
        except csv.Error:
            dialect = csv.excel
        reader = csv.reader(handle, dialect)
        header = next(reader, None)
        if not header:
            raise ValueError("The CSV file is empty")
        header = [name.strip() or "column_%d" % (index + 1) for index, name in enumerate(header)]
        rows = []
        for row in reader:
            if len(rows) >= limit:
                break
            if any(cell.strip() for cell in row):
                rows.append(row + [""] * (len(header) - len(row)))
    return header, rows


def _number(text):
    value = text.strip().replace(",", "").replace("_", "")
    if value.lower() in MISSING:
        return None
    if value.endswith("%"):
        value = value[:-1]
    try:
        number = float(value)
    except ValueError:
        return None
    return number if math.isfinite(number) else None


def _column(header, name):
    if name not in header:
        lowered = [column.lower() for column in header]
        if name.lower() not in lowered:
            raise ValueError("Unknown column %r. Columns: %s" % (name, ", ".join(header)))
        return lowered.index(name.lower())
    return header.index(name)


def describe_csv(path, max_columns=30):
    """Row count plus per-column statistics: numeric summary or the most frequent values."""
    header, rows = _read(path)
    columns = []
    for index, name in enumerate(header[:max(1, int(max_columns))]):
        cells = [row[index].strip() for row in rows]
        present = [cell for cell in cells if cell.lower() not in MISSING]
        numbers = [number for number in (_number(cell) for cell in present) if number is not None]
        summary = {"name": name, "count": len(present), "missing": len(cells) - len(present)}
        if present and len(numbers) >= 0.9 * len(present):
            summary.update({
                "type": "number",
                "mean": round(statistics.fmean(numbers), 6),
                "median": round(statistics.median(numbers), 6),
                "std": round(statistics.pstdev(numbers), 6) if len(numbers) > 1 else 0.0,
                "min": min(numbers),
                "max": max(numbers),
                "sum": round(math.fsum(numbers), 6),
            })
        else:
            counts = {}
            for cell in present:
                counts[cell] = counts.get(cell, 0) + 1
            top = sorted(counts.items(), key=lambda item: (-item[1], item[0]))[:5]
            summary.update({"type": "text", "unique": len(counts), "top": [{"value": value, "count": count} for value, count in top]})
        columns.append(summary)
    return {"file": path, "rows": len(rows), "columns": columns, "column_names": header}


def chart_csv(path, x, y=None, kind="bar", title="", output="chart.png", aggregate="sum"):
    """Bar (aggregated by category), line, scatter or histogram chart saved as a PNG in the chat folder."""
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    if kind not in ("bar", "line", "scatter", "hist"):
        raise ValueError("kind must be bar, line, scatter or hist")
    if not output.lower().endswith(".png") or "/" in output or "\\" in output:
        raise ValueError("output must be a simple .png file name")
    header, rows = _read(path)
    xi = _column(header, x)
    figure, axes = plt.subplots(figsize=(8, 4.5), dpi=144)
    if kind == "hist":
        values = [number for number in (_number(row[xi]) for row in rows) if number is not None]
        if not values:
            raise ValueError("Column %r has no numbers" % x)
        axes.hist(values, bins=min(30, max(5, int(math.sqrt(len(values))))), color="#4D6BFE")
        axes.set_xlabel(header[xi])
        axes.set_ylabel("Count")
        points = len(values)
    else:
        if y is None:
            raise ValueError("y is required for %s charts" % kind)
        yi = _column(header, y)
        pairs = [(row[xi].strip(), _number(row[yi])) for row in rows]
        pairs = [(label, value) for label, value in pairs if value is not None and label]
        if not pairs:
            raise ValueError("No rows have both %r and a numeric %r" % (x, y))
        if kind == "bar":
            totals, seen = {}, {}
            for label, value in pairs:
                totals[label] = totals.get(label, 0.0) + value
                seen[label] = seen.get(label, 0) + 1
            labels = list(totals)[:40]
            heights = [totals[label] / seen[label] if aggregate == "mean" else totals[label] for label in labels]
            axes.bar(range(len(labels)), heights, color="#4D6BFE")
            axes.set_xticks(range(len(labels)))
            axes.set_xticklabels(labels, rotation=45 if len(labels) > 6 else 0, ha="right" if len(labels) > 6 else "center")
            points = len(labels)
        else:
            numeric_x = [_number(label) for label, _ in pairs]
            xs = numeric_x if all(value is not None for value in numeric_x) else list(range(len(pairs)))
            ys = [value for _, value in pairs]
            if kind == "line":
                axes.plot(xs, ys, color="#4D6BFE", linewidth=2)
            else:
                axes.scatter(xs, ys, color="#4D6BFE", s=18)
            if xs is not numeric_x:
                step = max(1, len(pairs) // 10)
                axes.set_xticks(list(range(0, len(pairs), step)))
                axes.set_xticklabels([pairs[index][0] for index in range(0, len(pairs), step)], rotation=45, ha="right")
            points = len(pairs)
        axes.set_xlabel(header[xi])
        axes.set_ylabel(header[yi])
    axes.set_title(title or ("%s of %s" % (kind.capitalize(), header[xi])))
    axes.grid(axis="y", alpha=0.3)
    figure.tight_layout()
    figure.savefig(output)
    plt.close(figure)
    return {"file": output, "kind": kind, "points": points}
