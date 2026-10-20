package app.dsandroid.tools

/** Where a tool can run: the DeepSeek account (tool protocol over the web chat) and the API-key client (function calling). */
enum class ToolRoute { ACCOUNT, API }

enum class ToolGroup(val label: String) {
    FILES("Files and documents"), CODE("Code and skills"), WEB("Web"), PHONE("Phone"), ASSISTANT("Memory and tasks"),
}

/**
 * One switch in Plugins & tools. [names] are the tool names the switch covers: the first is the main name, the rest are
 * aliases with the same capability (an older name, or a set of steps that only work together). [approval] says when the
 * tool asks first; null means it works on this phone's private chat folder without asking. A [trusted] tool runs native
 * code with the app's rights: it is offered only while trusted execution is on, and then every call asks first.
 */
data class CatalogTool(
    val names: List<String>, val label: String, val summary: String, val group: ToolGroup, val routes: Set<ToolRoute>,
    val approval: String? = null, val trusted: Boolean = false,
) {
    val name: String get() = names.first()
}

/**
 * The one list of built-in tools that Settings, the Plugins & tools screen and both agent runs read, so a switch can never
 * drift from what the model is offered. A switched-off tool is left out of the model's tool list and refused if the model
 * asks for it anyway. Tools that are not built in (remote MCP tools, skill functions, connectors) are switched by name.
 */
object ToolCatalog {
    private val BOTH = setOf(ToolRoute.ACCOUNT, ToolRoute.API)
    private val API = setOf(ToolRoute.API)

    val entries: List<CatalogTool> = listOf(
        CatalogTool(listOf("write"), "Write files", "Creates or replaces text and code files in this chat's folder.", ToolGroup.FILES, BOTH),
        CatalogTool(listOf("read"), "Read files", "Reads files in this chat's folder.", ToolGroup.FILES, BOTH),
        CatalogTool(listOf("present"), "Show files", "Shows finished files as cards you can preview, save and share.", ToolGroup.FILES, BOTH),
        CatalogTool(listOf("write_file"), "Documents", "Word (.docx), CSV, Markdown, text, HTML and JSON files.", ToolGroup.FILES, BOTH),
        CatalogTool(listOf("create_pdf"), "PDF", "Lays out Markdown with headings, lists, tables and images as a PDF.", ToolGroup.FILES, BOTH),
        CatalogTool(listOf("create_xlsx", "create_spreadsheet"), "Excel workbooks", "Sheets with text and number cells (.xlsx).", ToolGroup.FILES, BOTH),
        CatalogTool(listOf("create_pptx", "create_presentation"), "PowerPoint decks", "Title and content slides with speaker notes (.pptx).", ToolGroup.FILES, BOTH),
        CatalogTool(listOf("create_chart", "render_chart"), "Charts", "Bar, line and pie charts as SVG images.", ToolGroup.FILES, BOTH),
        CatalogTool(listOf("create_image"), "PNG images", "Renders an SVG drawing or chart as a PNG image.", ToolGroup.FILES, BOTH),
        CatalogTool(listOf("zip_files"), "ZIP archives", "Packs files from this chat's folder into a ZIP.", ToolGroup.FILES, BOTH),
        CatalogTool(listOf("create_docx", "create_document"), "Word documents (structured)", "Word files from headings, paragraphs, lists and tables.", ToolGroup.FILES, API),
        CatalogTool(listOf("create_markdown"), "Markdown files", "Creates a new Markdown file.", ToolGroup.FILES, API),
        CatalogTool(listOf("create_csv"), "CSV files", "Creates a new CSV table.", ToolGroup.FILES, API),
        CatalogTool(listOf("create_svg"), "SVG images", "Creates a static SVG; scripts and remote resources are removed.", ToolGroup.FILES, API),
        CatalogTool(listOf("create_file"), "Text files", "Creates a new text file in the workspace.", ToolGroup.FILES, API),
        CatalogTool(listOf("read_file", "list_dir", "list_files"), "Browse the workspace", "Lists folders and reads text files in the workspace.", ToolGroup.FILES, API),
        CatalogTool(listOf("list_zip"), "Look inside ZIP files", "Lists the entries of a ZIP without extracting it.", ToolGroup.FILES, API),
        CatalogTool(listOf("create_artifact"), "Artifact canvas", "HTML, SVG, Mermaid, Markdown, CSV or JSON in the preview canvas.", ToolGroup.FILES, API),
        CatalogTool(listOf("create_web_project"), "Web projects", "Multi-file websites and HTML slide decks, previewed offline.", ToolGroup.FILES, API),
        CatalogTool(listOf("code_interpreter"), "Code sandbox", "JavaScript, or Python after a one-time verified download, in a disposable WebView worker with no network, app files or Android access.",
            ToolGroup.CODE, BOTH, approval = "Asks before downloading the Python runtime"),
        CatalogTool(listOf("run_python"), "Python on this phone", "Python 3.13 with numpy and matplotlib in this app's Python worker, with the app's file access.",
            ToolGroup.CODE, BOTH, approval = "Asks before each run", trusted = true),
        CatalogTool(listOf("pip_install"), "Python packages", "Installs pure-Python packages from PyPI into this app.", ToolGroup.CODE, BOTH,
            approval = "Asks before each download", trusted = true),
        CatalogTool(listOf("skill"), "Skills", "Loads the instructions of an enabled skill when a task matches it.", ToolGroup.CODE, BOTH),
        CatalogTool(listOf("web_search"), "Web search", "Searches the web and returns result pages with their links.", ToolGroup.WEB, BOTH,
            approval = "Asks before each search"),
        CatalogTool(listOf("web_fetch", "visit_web"), "Read web pages", "Reads the text of a public web page.", ToolGroup.WEB, BOTH,
            approval = "Asks before pages that did not come from the current search"),
        CatalogTool(listOf("open_url", "read_page", "click", "type", "submit", "scroll", "back"), "In-app browser",
            "Opens and operates a page in an isolated browser inside the app.", ToolGroup.WEB, API, approval = "Asks before opening pages and sending forms"),
        CatalogTool(listOf("draft_sms"), "Draft a text message", "Opens your messaging app with a draft; nothing is sent.", ToolGroup.PHONE, API, approval = "Asks first"),
        CatalogTool(listOf("draft_email"), "Draft an email", "Opens your email app with a draft; nothing is sent.", ToolGroup.PHONE, API, approval = "Asks first"),
        CatalogTool(listOf("add_calendar_event"), "Add a calendar event", "Opens your calendar with a new event to review.", ToolGroup.PHONE, API, approval = "Asks first"),
        CatalogTool(listOf("open_map"), "Open a map", "Opens a place or route in your maps app.", ToolGroup.PHONE, API, approval = "Asks first"),
        CatalogTool(listOf("remember"), "Memory", "Proposes facts to remember in later chats.", ToolGroup.ASSISTANT, API, approval = "Asks before saving"),
        CatalogTool(listOf("schedule_prompt"), "Scheduled prompts", "Runs a prompt later as a task on this phone.", ToolGroup.ASSISTANT, API, approval = "Asks first"),
        CatalogTool(listOf("parallel_agents"), "Parallel research", "Splits research into read-only sub-agents.", ToolGroup.ASSISTANT, API),
    )

    private val byName: Map<String, CatalogTool> = entries.flatMap { entry -> entry.names.map { it to entry } }.toMap()

    init { require(byName.size == entries.sumOf { it.names.size }) { "A tool name is in two catalog entries" } }

    fun entry(name: String): CatalogTool? = byName[name]

    fun forRoute(route: ToolRoute): List<CatalogTool> = entries.filter { route in it.routes }

    /** A catalog tool is on unless any of its names was switched off; other tools are on unless their own name was. */
    fun isEnabled(name: String, disabled: Set<String>): Boolean = entry(name)?.names?.none { it in disabled } ?: (name !in disabled)

    /** Every name to leave out of a registry: all names of a switched-off catalog tool, plus other switched-off names. */
    fun disabledNames(disabled: Set<String>): Set<String> =
        disabled + disabled.mapNotNull { entry(it) }.flatMap { it.names }

    /** The stored set after a switch: off records the main name; on clears every name the switch covers. */
    fun switched(disabled: Set<String>, name: String, on: Boolean): Set<String> {
        val names = entry(name)?.names ?: listOf(name)
        return if (on) disabled - names.toSet() else disabled - names.toSet() + names.first()
    }
}
