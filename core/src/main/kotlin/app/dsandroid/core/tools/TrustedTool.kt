package app.dsandroid.core.tools

/**
 * Native code that runs with this app's rights on the phone (run_python, pip_install, skill functions). It is not a
 * sandbox, so a run offers these tools only when trusted execution is switched on, and every call asks first.
 */
class TrustedTool(val tool: Tool) : Tool by tool {
    override val risk = Risk.CONFIRM
    override val description = tool.description + " Trusted execution: runs with this app's rights on the phone, not in a sandbox; every call asks first."
}
