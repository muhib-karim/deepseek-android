# Data analysis

Use this skill when the user wants statistics, trends or charts from tabular data (CSV or a table in the chat).

## Workflow

1. Get the data into this chat's folder as a CSV file.
   - If the user pasted a table or CSV text, save it with `write_file` (for example `data.csv`).
   - If a file already exists, check its name with `read` (first lines only, `limit` 5).
2. Call `data_analysis__describe_csv` with the file path. Read the column types, missing values and ranges before drawing conclusions.
3. Draw one or two charts that answer the question with `data_analysis__chart_csv`:
   - `bar` to compare categories (rows with the same category are summed; use `aggregate: "mean"` for averages),
   - `line` for values over time, `scatter` for relationships, `hist` for a distribution.
   - Give each chart a clear `title` and a distinct `output` name.
4. For anything the tools do not cover (grouping by two columns, correlations, filtering), use `run_python` with the standard library or numpy and print compact results.
5. Answer with the key findings first, in plain language, with the actual numbers. Mention data quality issues (missing values, odd outliers).
6. If the user wants a report, call `create_pdf` and embed charts on their own line: `![Revenue by region](chart.png)`.

Never invent values that are not in the data. Charts appear to the user as file cards automatically.
