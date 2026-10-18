# PDF report

Use this skill when the user asks for a report, brief, handout or summary document, or says "make a PDF".

## Steps

1. Collect the facts first: the conversation, files in this chat (`read`), and web pages (`web_fetch`) when the user gave links or asked for current information. Do not invent numbers, quotes or sources.
2. Plan the document:
   - a short, specific title;
   - a **Summary** with 3-5 bullet points that state the main findings;
   - sections with `##` headings and short paragraphs;
   - Markdown tables (header row, then rows) for comparisons and figures;
   - a **Sources** section with numbered links when you used the web.
3. If numbers benefit from a chart, make a PNG first with `run_python` (matplotlib, `plt.savefig("chart.png")`) or the data-analysis skill, then embed it on its own line: `![What the chart shows](chart.png)`.
4. Call `create_pdf` with `title` and the full Markdown in `markdown`. Put `\pagebreak` on its own line to start a new page before long appendices.
5. After the tool result, tell the user in one or two sentences what the PDF contains. The file card lets them open, save or share it.

## Style

Plain, precise language; consistent tense; units on every number; no emoji. Keep paragraphs under about 120 words and prefer lists for sequences of facts.
