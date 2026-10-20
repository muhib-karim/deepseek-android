package app.dsandroid.core.sandbox

import kotlinx.serialization.json.JsonPrimitive

/** A trusted bootstrap only: generated code is a string consumed inside a dedicated worker. */
object CodeSandboxDocument {
    /**
     * The code page's origin. A real (reserved, never resolved) https origin, so the blob worker is same-origin with its
     * page; every request the page or worker makes is intercepted by the Android adapter and only cached runtime files
     * are ever answered.
     */
    const val BASE_URL = "https://sandbox.invalid/"

    fun javascriptString(text: String): String = JsonPrimitive(text).toString()
        .replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
        .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

    fun javascript(code: String, pollKey: String): String = document(code, pollKey, false)

    /** The platform serves exact pinned runtime files from cache, never from the network. */
    fun python(code: String, pollKey: String): String = document(code, pollKey, true)

    /** The page: a script-free shell (carrying the CSP) and the trusted bootstrap the host runs in it once it has loaded. */
    class Page(val shell: String, val bootstrap: String)

    fun javascriptPage(code: String, pollKey: String): Page = page(code, pollKey, false)

    fun pythonPage(code: String, pollKey: String): Page = page(code, pollKey, true)

    /** The same page as one self-contained document, with the bootstrap inline. */
    private fun document(code: String, pollKey: String, python: Boolean): String =
        page(code, pollKey, python).let { it.shell.replace("<body></body>", "<body><script>\n" + it.bootstrap + "\n</script></body>") }

    private fun page(code: String, pollKey: String, python: Boolean): Page {
        require(code.isNotBlank() && code.length <= CodeLimits.MAX_CODE_CHARS)
        require(pollKey.matches(Regex("[A-Za-z][A-Za-z0-9]{8,80}")))
        val source = """
            (() => {
              'use strict';
              const send = self.postMessage.bind(self), finishWorker = self.close.bind(self);
              const code = ${javascriptString(code)}, python = $python;
              const limit = ${CodeLimits.MAX_OUTPUT_CHARS};
              let remaining = limit, stdout = '', stderr = '', truncated = false;
              const clamp = text => {
                text = String(text);
                const kept = text.slice(0, remaining); remaining -= kept.length;
                if (kept.length !== text.length) truncated = true;
                return kept;
              };
              const printable = value => {
                if (typeof value === 'string') return value;
                if (value === undefined) return 'undefined';
                try { return JSON.stringify(value) ?? String(value); } catch (_) { return String(value); }
              };
              const log = (...args) => { for (let i=0;i<args.length && remaining>0;i++) {
                stdout += clamp((i ? ' ' : '') + printable(args[i]));
              } stdout += clamp('\n'); };
              const error = (...args) => { for (let i=0;i<args.length && remaining>0;i++) {
                stderr += clamp((i ? ' ' : '') + printable(args[i]));
              } stderr += clamp('\n'); };
              const lock = () => {
                for (const name of ['fetch','XMLHttpRequest','WebSocket','WebTransport','EventSource',
                    'importScripts','Worker','SharedWorker','MessageChannel','BroadcastChannel',
                    'indexedDB','caches','navigator','postMessage','onmessage','onmessageerror']) {
                  try { Object.defineProperty(self,name,{value:undefined,writable:false,configurable:false}); } catch (_) {}
                }
                Object.defineProperty(self,'console',{value:Object.freeze({log,info:log,warn:error,error,debug:log}),writable:false,configurable:false});
              };
              (async () => {
                let value, failed = false;
                try {
                  if (!python) {
                    lock();
                    const AsyncFunction = Object.getPrototypeOf(async function(){}).constructor;
                    value = await new AsyncFunction(code)();
                  } else {
                    self.importScripts('${PythonRuntime.CACHE_BASE_URL}pyodide.js');
                    const pyodide = await self.loadPyodide({
                      indexURL: '${PythonRuntime.CACHE_BASE_URL}',
                      stdLibURL: '${PythonRuntime.CACHE_BASE_URL}python_stdlib.zip',
                      stdout: text => log(text), stderr: text => error(text)
                    });
                    lock();
                    const result = await pyodide.runPythonAsync(code);
                    try { value = result === undefined ? undefined : String(result); }
                    finally { if (result && typeof result.destroy === 'function') result.destroy(); }
                  }
                } catch (e) { failed = true; try { error(String(e)); } catch (_) { stderr += clamp('Execution failed'); } }
                let rendered;
                try { if (value !== undefined) rendered = clamp(printable(value)); }
                catch (_) { failed = true; stderr += clamp('Cannot display return value'); }
                send({stdout,stderr,value:rendered === undefined ? null : rendered,
                  hasValue: rendered !== undefined,truncated,failed});
                finishWorker();
              })();
            })();
        """.trimIndent()
        // Blob workers inherit this CSP. Python resources are intercepted cache reads;
        // the adapter must reject every other request, including redirects and queries.
        val cached = if (python) " ${PythonRuntime.CACHE_BASE_URL}" else ""
        val policy = "default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' blob:$cached; worker-src blob:; " +
            "connect-src ${if (python) PythonRuntime.CACHE_BASE_URL else "'none'"}; style-src 'none'; img-src 'none'; media-src 'none'; frame-src 'none'; " +
            "object-src 'none'; base-uri 'none'; form-action 'none'"
        val shell = "<!doctype html><html><head><meta charset=\"utf-8\"><meta http-equiv=\"Content-Security-Policy\" content=\"$policy\"></head><body></body></html>"
        val bootstrap = """
            (() => {
              'use strict';
              let result = null;
              // Defined first: '' while the worker runs, then the result. A page that never gets here reads as not started.
              Object.defineProperty(window,${javascriptString(pollKey)},{value:() => result === null ? '' : result});
              const cap = ${CodeLimits.MAX_OUTPUT_CHARS};
              const fail = message => {
                if (result === null) result = JSON.stringify({stdout:'',stderr:String(message).slice(0,cap),value:null,hasValue:false,truncated:false,failed:true});
              };
              let worker;
              try {
                const workerUrl = URL.createObjectURL(new Blob([${javascriptString(source)}], {type:'text/javascript'}));
                worker = new Worker(workerUrl);
                URL.revokeObjectURL(workerUrl);
              } catch (e) { fail('The sandbox worker could not start: ' + e); return; }
              const finish = data => {
                if (result !== null) return;
                worker.terminate();
                let left = cap;
                const take = value => { const s = typeof value === 'string' ? value : ''; const t = s.slice(0,left);left -= t.length;return t; };
                const stdout = take(data.stdout), stderr = take(data.stderr), value = data.hasValue === true ? take(data.value) : null;
                result = JSON.stringify({stdout,stderr,value,hasValue:data.hasValue === true,
                  truncated:data.truncated === true || left === 0,failed:data.failed === true});
              };
              worker.onmessage = event => {
                const data = event.data;
                if (data && typeof data === 'object' && typeof data.stdout === 'string' &&
                    typeof data.stderr === 'string' && typeof data.failed === 'boolean') finish(data);
              };
              worker.onerror = event => finish({stdout:'',stderr:'Worker execution failed' + (event && event.message ? ': ' + event.message : ''),failed:true});
            })();
            """.trimIndent()
        return Page(shell, bootstrap)
    }
}
