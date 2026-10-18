"""run_python worker for DeepSeek Android (Chaquopy, separate :python process).

Runs model or user code in a per-chat namespace and working directory, captures bounded
stdout/stderr, evaluates a trailing expression like a REPL, saves open matplotlib figures as
PNG files and refuses socket connections. The Kotlin side owns the wall-clock limit: it kills
this process on timeout, which also clears every namespace.
"""
import ast
import importlib
import io
import json
import os
import sys
import traceback

MAX_OUTPUT = 16000
IMAGE_SUFFIXES = (".png", ".jpg", ".jpeg", ".svg", ".pdf", ".gif", ".webp")

_namespaces = {}
_site_dirs = set()
_cwd_entry = None
_network_blocked = False


class _Capture(io.TextIOBase):
    """A text stream that keeps at most `limit` characters and remembers truncation."""

    def __init__(self, limit):
        super().__init__()
        self._parts = []
        self._size = 0
        self._limit = limit
        self.truncated = False

    def writable(self):
        return True

    def write(self, text):
        if not isinstance(text, str):
            text = str(text)
        room = self._limit - self._size
        if room > 0:
            piece = text[:room]
            self._parts.append(piece)
            self._size += len(piece)
            if len(piece) < len(text):
                self.truncated = True
        elif text:
            self.truncated = True
        return len(text)

    def getvalue(self):
        return "".join(self._parts)


def _refuse_network(*args, **kwargs):
    raise PermissionError(
        "Network access is disabled in run_python. Use the web_fetch tool for pages "
        "or pip_install for pure-Python packages.")


def _block_network():
    global _network_blocked
    if _network_blocked:
        return
    import socket
    socket.socket.connect = _refuse_network
    socket.socket.connect_ex = _refuse_network
    socket.create_connection = _refuse_network
    socket.getaddrinfo = _refuse_network
    _network_blocked = True


def _prepare(cwd, site_dir, mpl_dir):
    global _cwd_entry
    os.environ.setdefault("MPLBACKEND", "Agg")
    if mpl_dir:
        os.makedirs(mpl_dir, exist_ok=True)
        os.environ["MPLCONFIGDIR"] = mpl_dir
    if site_dir and site_dir not in _site_dirs:
        os.makedirs(site_dir, exist_ok=True)
        sys.path.append(site_dir)
        _site_dirs.add(site_dir)
    _block_network()
    os.makedirs(cwd, exist_ok=True)
    os.chdir(cwd)
    # Let code import modules it wrote into this chat's folder, and only this chat's folder.
    if _cwd_entry is not None and _cwd_entry in sys.path:
        sys.path.remove(_cwd_entry)
    sys.path.insert(0, cwd)
    _cwd_entry = cwd
    importlib.invalidate_caches()


def _images(cwd):
    try:
        return {name for name in os.listdir(cwd) if name.lower().endswith(IMAGE_SUFFIXES)}
    except OSError:
        return set()


def _save_figures(cwd, images_before):
    """Saves figures left open, unless the code already wrote an image itself; always closes them."""
    pyplot = sys.modules.get("matplotlib.pyplot")
    if pyplot is None:
        return []
    saved = []
    try:
        numbers = pyplot.get_fignums()
        if numbers and not (_images(cwd) - images_before):
            for number in numbers:
                name = "figure_%d.png" % number
                index = 2
                while os.path.exists(os.path.join(cwd, name)):
                    name = "figure_%d_%d.png" % (number, index)
                    index += 1
                pyplot.figure(number).savefig(os.path.join(cwd, name), dpi=144, bbox_inches="tight")
                saved.append(name)
    finally:
        pyplot.close("all")
    return saved


def run(code, cwd, site_dir, namespace, mpl_dir):
    """Executes `code` and returns a JSON object with stdout, stderr, error, truncated and figures."""
    _prepare(cwd, site_dir, mpl_dir)
    images_before = _images(cwd)
    out = _Capture(MAX_OUTPUT)
    err = _Capture(MAX_OUTPUT)
    scope = _namespaces.setdefault(namespace, {"__name__": "__main__", "__builtins__": __builtins__})
    error = None
    saved_out, saved_err = sys.stdout, sys.stderr
    sys.stdout, sys.stderr = out, err
    try:
        tree = ast.parse(code, "<run_python>", "exec")
        last = None
        if tree.body and isinstance(tree.body[-1], ast.Expr):
            last = ast.Expression(tree.body.pop().value)
        exec(compile(tree, "<run_python>", "exec"), scope)
        if last is not None:
            value = eval(compile(last, "<run_python>", "eval"), scope)
            if value is not None:
                print(repr(value))
    except SystemExit as stop:
        if stop.code not in (None, 0):
            error = "SystemExit: %s" % (stop.code,)
    except BaseException as exc:  # Report everything, including KeyboardInterrupt, to the model.
        traceback.print_exc()
        error = "%s: %s" % (type(exc).__name__, exc)
    finally:
        sys.stdout, sys.stderr = saved_out, saved_err
    figures = []
    try:
        figures = _save_figures(cwd, images_before)
    except Exception as exc:
        err.write("Could not save matplotlib figures: %s\n" % (exc,))
    return json.dumps({
        "stdout": out.getvalue(),
        "stderr": err.getvalue(),
        "error": error,
        "truncated": out.truncated or err.truncated,
        "figures": figures,
    })
