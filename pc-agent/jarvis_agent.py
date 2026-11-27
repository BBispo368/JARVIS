#!/usr/bin/env python3
"""
Agente do Jarvis no PC (Fase 2).

Servidor HTTP mínimo (só biblioteca padrão, Python 3.9+) que expõe
ferramentas para o app Jarvis do celular, na rede local:

    GET  /health             -> {"ok": true}
    GET  /tools              -> {"tools": [{name, label, description, input_schema}, ...]}
    POST /tools/<nome>       -> body {"input": {...}} -> {"ok": true, "result": "..."}
                                                      ou {"ok": false, "error": "..."}

Toda requisição precisa do header "Authorization: Bearer <token>".

Segurança: o modelo NÃO roda comandos arbitrários. Ele só escolhe, pelo
nome, apps e atalhos que você mesmo cadastrou no config.json.

Descoberta: o agente também escuta UDP na porta 8766. O app manda um
broadcast "JARVIS_DISCOVER_V1" e o agente responde com a porta HTTP — assim
o celular acha o PC mesmo se o IP mudar. O token nunca vai nessa resposta.

Uso:
    python jarvis_agent.py                      # cria o config.json na 1ª vez e sobe o servidor
    python jarvis_agent.py --config caminho/config.json
    python jarvis_agent.py --install-autostart  # inicia sozinho quando você entra no PC
    python jarvis_agent.py --uninstall-autostart
"""

from __future__ import annotations

import argparse
import base64
import hmac
import json
import os
import platform
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time
import unicodedata
import webbrowser
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional

SYSTEM = platform.system()  # "Windows", "Darwin" ou "Linux"
MAX_BODY = 64 * 1024
MAX_OUTPUT = 1500
DEFAULT_CONFIG_PATH = Path(__file__).resolve().with_name("config.json")
DISCOVERY_MAGIC = b"JARVIS_DISCOVER_V1"
AUTOSTART_ID = "dev.breno.jarvis.agent"


class ToolError(Exception):
    """Erro "esperado" de ferramenta: a mensagem vai pro Claude explicar ao usuário."""


# --------------------------------------------------------------------------
# Configuração
# --------------------------------------------------------------------------

def default_config() -> Dict[str, Any]:
    return {
        "host": "0.0.0.0",
        "port": 8765,
        "discovery_port": 8766,
        "token": secrets.token_urlsafe(24),
        # nome falado -> comando do seu sistema (roda via shell)
        # Acha sozinho os programas instalados (menu Iniciar / Aplicativos) para o pc_open_app.
        "auto_apps": True,
        "apps": {},
        # nome -> {"description": "...", "command": "..."}
        "shortcuts": {},
    }


def load_config(path: Path) -> Dict[str, Any]:
    if not path.exists():
        cfg = default_config()
        path.write_text(json.dumps(cfg, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"[jarvis] Criei {path} com um token novo. Edite 'apps' e 'shortcuts' quando quiser.")
        return cfg
    cfg = json.loads(path.read_text(encoding="utf-8"))
    base = default_config()
    base.update(cfg)
    if not str(base.get("token", "")).strip():
        raise SystemExit(f"[jarvis] 'token' vazio em {path}. Coloque um valor ou apague o arquivo para gerar outro.")
    return base


# --------------------------------------------------------------------------
# Utilidades
# --------------------------------------------------------------------------

def run(cmd: List[str], input_text: Optional[str] = None, timeout: int = 15) -> str:
    try:
        proc = subprocess.run(
            cmd, input=input_text, capture_output=True, text=True, timeout=timeout,
            encoding="utf-8", errors="replace",
        )
    except FileNotFoundError:
        raise ToolError(f"programa '{cmd[0]}' não encontrado neste PC")
    except subprocess.TimeoutExpired:
        raise ToolError("o comando demorou demais e foi interrompido")
    if proc.returncode != 0:
        detail = (proc.stderr or proc.stdout or "").strip()[:300]
        raise ToolError(f"o comando falhou (código {proc.returncode}) {detail}".strip())
    return proc.stdout.strip()


def which_first(*names: str) -> Optional[str]:
    for n in names:
        if shutil.which(n):
            return n
    return None


def truncate(text: str, limit: int = MAX_OUTPUT) -> str:
    return text if len(text) <= limit else text[:limit] + "… (cortado)"


def lan_ip() -> str:
    """IP do PC na rede local (não envia pacote nenhum, só consulta a rota)."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def obj_schema(props: Dict[str, Dict[str, Any]]) -> Dict[str, Any]:
    return {
        "type": "object",
        "properties": props,
        "required": list(props.keys()),
        "additionalProperties": False,
    }


# --------------------------------------------------------------------------
# Ferramentas
# --------------------------------------------------------------------------

@dataclass
class Tool:
    name: str
    label: str
    description: str
    input_schema: Dict[str, Any]
    handler: Callable[[Dict[str, Any]], str]

    def describe(self) -> Dict[str, Any]:
        return {
            "name": self.name,
            "label": self.label,
            "description": self.description,
            "input_schema": self.input_schema,
        }


def tool_open_url(inp: Dict[str, Any]) -> str:
    url = str(inp.get("url", "")).strip()
    if not (url.startswith("http://") or url.startswith("https://")):
        raise ToolError("só abro endereços http:// ou https://")
    if not webbrowser.open(url):
        raise ToolError("não encontrei um navegador para abrir o link")
    return f"Abri {url} no navegador."


# --------------------------------------------------------------------------
# Programas instalados (para o pc_open_app sem precisar cadastrar nada)
# --------------------------------------------------------------------------

Launcher = Callable[[], None]

# Atalhos do menu Iniciar que não são programas de verdade.
_JUNK = ("desinstal", "uninstall", "leia-me", "leiame", "readme", "release notes", "notas de vers",
         "documenta", "manual", "help", "ajuda", "website", "site da web", "license", "licen")


def normalize(text: str) -> str:
    """'Microsoft Edge®' -> 'microsoft edge'; tira acentos, pontuação e caixa."""
    text = unicodedata.normalize("NFKD", text)
    text = "".join(c for c in text if not unicodedata.combining(c)).lower()
    return " ".join("".join(c if c.isalnum() else " " for c in text).split())


def _popen(args: List[str]) -> None:
    subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def discover_installed_apps() -> Dict[str, Launcher]:
    """Programas instalados -> como abrir. Nunca lança erro: sem lista, devolve {}."""
    apps: Dict[str, Launcher] = {}
    try:
        if SYSTEM == "Windows":
            # Get-StartApps lista o menu Iniciar inteiro, inclusive apps da Microsoft Store.
            ps = ("[Console]::OutputEncoding=[Text.Encoding]::UTF8; "
                  "Get-StartApps | Select-Object Name, AppID | ConvertTo-Json -Compress")
            out = run(["powershell", "-NoProfile", "-NonInteractive", "-Command", ps], timeout=30)
            data = json.loads(out) if out else []
            for item in data if isinstance(data, list) else [data]:
                name, app_id = str(item.get("Name", "")).strip(), str(item.get("AppID", "")).strip()
                if name and app_id:
                    apps.setdefault(name, lambda a=app_id: _popen(["explorer.exe", f"shell:AppsFolder\\{a}"]))
        elif SYSTEM == "Darwin":
            roots = [Path("/Applications"), Path("/System/Applications"), Path.home() / "Applications"]
            for root in roots:
                for app in sorted(root.glob("*.app")) + sorted(root.glob("*/*.app")):
                    apps.setdefault(app.stem, lambda p=str(app): _popen(["open", "-a", p]))
        else:
            roots = [Path("/usr/share/applications"), Path.home() / ".local/share/applications",
                     Path("/var/lib/flatpak/exports/share/applications")]
            launcher = which_first("gtk-launch", "gio")
            for root in roots:
                for desktop in sorted(root.glob("*.desktop")):
                    name, hidden = _desktop_entry(desktop)
                    if not name or hidden or not launcher:
                        continue
                    if launcher == "gtk-launch":
                        apps.setdefault(name, lambda d=desktop.name: _popen(["gtk-launch", d]))
                    else:
                        apps.setdefault(name, lambda d=str(desktop): _popen(["gio", "launch", d]))
    except Exception as e:  # noqa: BLE001 - lista de apps é um extra; o agente sobe mesmo sem ela
        print(f"[jarvis] Não consegui listar os programas instalados: {e}")
        return {}
    return {n: f for n, f in apps.items() if not any(j in normalize(n) for j in _JUNK)}


def _desktop_entry(path: Path):
    name, hidden, in_entry = None, False, False
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if line.startswith("["):
            in_entry = line == "[Desktop Entry]"
        elif in_entry and line.startswith("Name=") and name is None:
            name = line[5:].strip()
        elif in_entry and line in ("NoDisplay=true", "Hidden=true"):
            hidden = True
    return name, hidden


def find_apps(query: str, names: List[str]) -> List[str]:
    """Nomes que combinam com o pedido, do mais provável ao menos provável."""
    q = normalize(query)
    if not q:
        return []
    norm = {n: normalize(n) for n in names}
    exact = [n for n in names if norm[n] == q]
    if exact:
        return exact
    q_tokens = q.split()
    hits = [n for n in names if q in norm[n] or all(t in norm[n].split() for t in q_tokens)]
    # Prefere nomes que começam com o pedido e nomes mais curtos ("Spotify" antes de "Spotify Widget").
    return sorted(hits, key=lambda n: (not norm[n].startswith(q), len(n)))


def make_open_app(manual: Dict[str, str], installed: Callable[[], Dict[str, Launcher]]):
    def launch_manual(cmd: str) -> Launcher:
        # Comando escrito por você no config; o modelo só escolhe o nome.
        return lambda: subprocess.Popen(cmd, shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def handler(inp: Dict[str, Any]) -> str:
        query = str(inp.get("name", "")).strip()
        catalog: Dict[str, Launcher] = {n: launch_manual(c) for n, c in manual.items()}
        for n, f in installed().items():
            catalog.setdefault(n, f)
        matches = find_apps(query, list(catalog))
        if not matches:
            raise ToolError(f"não achei nenhum programa instalado parecido com '{query}'")
        best = matches[0]
        ambiguous = (len(matches) > 1 and normalize(best) != normalize(query)
                     and not normalize(best).startswith(normalize(query)))
        if ambiguous:
            raise ToolError("achei mais de um programa parecido: " + ", ".join(matches[:6])
                            + ". Chame de novo com o nome exato.")
        catalog[best]()
        return f"Abrindo {best}."
    return handler


def make_run_shortcut(shortcuts: Dict[str, Dict[str, str]]) -> Callable[[Dict[str, Any]], str]:
    def handler(inp: Dict[str, Any]) -> str:
        name = str(inp.get("name", ""))
        sc = shortcuts.get(name)
        if not sc or not sc.get("command"):
            raise ToolError(f"atalho '{name}' não está no config.json")
        try:
            proc = subprocess.run(
                sc["command"], shell=True, capture_output=True, text=True, timeout=60,
                encoding="utf-8", errors="replace",
            )
        except subprocess.TimeoutExpired:
            raise ToolError("o atalho demorou mais de 60 s e foi interrompido")
        out = (proc.stdout or "").strip()
        if proc.returncode != 0:
            raise ToolError(f"o atalho falhou (código {proc.returncode}) {truncate((proc.stderr or out).strip(), 300)}")
        return truncate(out) if out else f"Atalho {name} executado."
    return handler


MEDIA_ACTIONS = ["play_pause", "next", "previous", "volume_up", "volume_down", "mute"]


def tool_media(inp: Dict[str, Any]) -> str:
    action = str(inp.get("action", ""))
    if action not in MEDIA_ACTIONS:
        raise ToolError(f"ação de mídia inválida: {action}")

    if SYSTEM == "Windows":
        import ctypes  # noqa: PLC0415

        vk = {"play_pause": 0xB3, "next": 0xB0, "previous": 0xB1,
              "volume_up": 0xAF, "volume_down": 0xAE, "mute": 0xAD}[action]
        presses = 5 if action in ("volume_up", "volume_down") else 1  # cada toque = 2%
        for _ in range(presses):
            ctypes.windll.user32.keybd_event(vk, 0, 0, 0)
            ctypes.windll.user32.keybd_event(vk, 0, 2, 0)  # KEYEVENTF_KEYUP
    elif SYSTEM == "Darwin":
        scripts = {
            "volume_up": "set volume output volume ((output volume of (get volume settings)) + 10)",
            "volume_down": "set volume output volume ((output volume of (get volume settings)) - 10)",
            "mute": "set volume output muted (not (output muted of (get volume settings)))",
        }
        if action in scripts:
            run(["osascript", "-e", scripts[action]])
        else:
            verb = {"play_pause": "playpause", "next": "next track", "previous": "previous track"}[action]
            spotify = subprocess.run(["pgrep", "-x", "Spotify"], capture_output=True).returncode == 0
            app = "Spotify" if spotify else "Music"
            run(["osascript", "-e", f'tell application "{app}" to {verb}'])
    else:
        if action in ("volume_up", "volume_down", "mute"):
            if not shutil.which("pactl"):
                raise ToolError("preciso do 'pactl' (PulseAudio/PipeWire) para mexer no volume")
            arg = {"volume_up": "+10%", "volume_down": "-10%"}.get(action)
            if arg:
                run(["pactl", "set-sink-volume", "@DEFAULT_SINK@", arg])
            else:
                run(["pactl", "set-sink-mute", "@DEFAULT_SINK@", "toggle"])
        else:
            if not shutil.which("playerctl"):
                raise ToolError("preciso do 'playerctl' instalado para controlar a mídia")
            run(["playerctl", {"play_pause": "play-pause", "next": "next", "previous": "previous"}[action]])
    return f"Mídia: {action} feito."


def tool_lock_screen(_: Dict[str, Any]) -> str:
    if SYSTEM == "Windows":
        run(["rundll32.exe", "user32.dll,LockWorkStation"])
    elif SYSTEM == "Darwin":
        run(["pmset", "displaysleepnow"])
    else:
        if shutil.which("loginctl"):
            run(["loginctl", "lock-session"])
        elif shutil.which("xdg-screensaver"):
            run(["xdg-screensaver", "lock"])
        else:
            raise ToolError("não sei bloquear a tela neste Linux (instale loginctl ou xdg-screensaver)")
    return "Tela bloqueada."


def tool_clipboard(inp: Dict[str, Any]) -> str:
    text = str(inp.get("text", ""))
    if SYSTEM == "Windows":
        # Base64 evita problemas de encoding/aspas com acentos no PowerShell.
        b64 = base64.b64encode(text.encode("utf-8")).decode("ascii")
        ps = f"Set-Clipboard -Value ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('{b64}')))"
        run(["powershell", "-NoProfile", "-NonInteractive", "-Command", ps])
    elif SYSTEM == "Darwin":
        os.environ.setdefault("LANG", "en_US.UTF-8")
        run(["pbcopy"], input_text=text)
    else:
        tool = which_first("wl-copy", "xclip", "xsel")
        if tool == "wl-copy":
            run(["wl-copy"], input_text=text)
        elif tool == "xclip":
            run(["xclip", "-selection", "clipboard"], input_text=text)
        elif tool == "xsel":
            run(["xsel", "--clipboard", "--input"], input_text=text)
        else:
            raise ToolError("instale wl-copy, xclip ou xsel para usar a área de transferência")
    return f"Copiei {len(text)} caracteres para a área de transferência do PC."


def uptime_seconds() -> Optional[float]:
    try:
        if SYSTEM == "Windows":
            import ctypes  # noqa: PLC0415
            tick = ctypes.windll.kernel32.GetTickCount64
            tick.restype = ctypes.c_ulonglong  # o padrão (int de 32 bits) estouraria após ~25 dias
            return tick() / 1000
        if SYSTEM == "Linux":
            return float(Path("/proc/uptime").read_text().split()[0])
        if SYSTEM == "Darwin":
            out = run(["sysctl", "-n", "kern.boottime"])  # { sec = 1700000000, usec = 0 } ...
            sec = int(out.split("sec =")[1].split(",")[0])
            return time.time() - sec
    except Exception:  # noqa: BLE001 - informação opcional
        return None
    return None


def battery_percent() -> Optional[str]:
    try:
        if SYSTEM == "Linux":
            for bat in sorted(Path("/sys/class/power_supply").glob("BAT*")):
                return (bat / "capacity").read_text().strip() + "%"
        elif SYSTEM == "Darwin":
            out = run(["pmset", "-g", "batt"])
            for part in out.replace(";", " ").split():
                if part.endswith("%"):
                    return part
    except Exception:  # noqa: BLE001
        return None
    return None


# --------------------------------------------------------------------------
# Janelas abertas (listar, fechar, minimizar, maximizar, focar)
# --------------------------------------------------------------------------

@dataclass
class Window:
    handle: Any          # HWND no Windows; id do wmctrl no Linux; nome do app no macOS
    title: str
    process: str         # ex.: "chrome", "spotify"


WINDOW_ACTIONS = ["close", "minimize", "maximize", "focus"]
_SKIP_TITLES = {"program manager", "configuracoes", "settings", "windows input experience",
                "microsoft text input application", "nvidia geforce overlay"}


def list_windows() -> List[Window]:
    """Janelas de aplicativo visíveis (sem barras, popups invisíveis e a área de trabalho)."""
    if SYSTEM == "Windows":
        return _win_list()
    if SYSTEM == "Darwin":
        out = run(["osascript", "-e",
                   'tell application "System Events" to get name of every application process whose visible is true'])
        return [Window(n.strip(), n.strip(), n.strip().lower()) for n in out.split(",") if n.strip()]
    if not shutil.which("wmctrl"):
        raise ToolError("preciso do 'wmctrl' instalado para mexer nas janelas neste Linux")
    windows = []
    for line in run(["wmctrl", "-lp"]).splitlines():
        parts = line.split(None, 4)
        if len(parts) == 5:
            proc = ""
            try:
                proc = Path(f"/proc/{parts[2]}/comm").read_text().strip()
            except OSError:
                pass
            windows.append(Window(parts[0], parts[4], proc))
    return windows


def window_action(win: Window, action: str) -> None:
    if SYSTEM == "Windows":
        _win_action(win.handle, action)
    elif SYSTEM == "Darwin":
        scripts = {
            "close": f'tell application "{win.handle}" to quit',
            "focus": f'tell application "{win.handle}" to activate',
            "minimize": f'tell application "System Events" to set visible of process "{win.handle}" to false',
            "maximize": f'tell application "{win.handle}" to activate',
        }
        run(["osascript", "-e", scripts[action]])
    else:
        args = {"close": ["-ic", win.handle], "focus": ["-ia", win.handle],
                "minimize": ["-ir", win.handle, "-b", "add,hidden"],
                "maximize": ["-ir", win.handle, "-b", "add,maximized_vert,maximized_horz"]}[action]
        run(["wmctrl", *args])


_WIN_API: Dict[str, Any] = {}


def _win_api():
    """user32/kernel32 com os tipos declarados (sem isso, ponteiros de 64 bits seriam truncados)."""
    if _WIN_API:
        return _WIN_API["user32"], _WIN_API["kernel32"]
    import ctypes  # noqa: PLC0415
    from ctypes import wintypes as w  # noqa: PLC0415

    user32 = ctypes.WinDLL("user32", use_last_error=True)
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    protos = [
        (user32.IsWindowVisible, [w.HWND], w.BOOL),
        (user32.GetWindow, [w.HWND, w.UINT], w.HWND),
        (user32.GetWindowLongW, [w.HWND, ctypes.c_int], w.LONG),
        (user32.GetWindowTextLengthW, [w.HWND], ctypes.c_int),
        (user32.GetWindowTextW, [w.HWND, w.LPWSTR, ctypes.c_int], ctypes.c_int),
        (user32.GetWindowThreadProcessId, [w.HWND, ctypes.POINTER(w.DWORD)], w.DWORD),
        (user32.PostMessageW, [w.HWND, w.UINT, w.WPARAM, w.LPARAM], w.BOOL),
        (user32.ShowWindow, [w.HWND, ctypes.c_int], w.BOOL),
        (user32.IsIconic, [w.HWND], w.BOOL),
        (user32.SetForegroundWindow, [w.HWND], w.BOOL),
        (kernel32.OpenProcess, [w.DWORD, w.BOOL, w.DWORD], w.HANDLE),
        (kernel32.QueryFullProcessImageNameW, [w.HANDLE, w.DWORD, w.LPWSTR, ctypes.POINTER(w.DWORD)], w.BOOL),
        (kernel32.CloseHandle, [w.HANDLE], w.BOOL),
    ]
    for fn, args, res in protos:
        fn.argtypes, fn.restype = args, res
    _WIN_API.update(user32=user32, kernel32=kernel32)
    return user32, kernel32


def _win_list() -> List[Window]:
    import ctypes  # noqa: PLC0415
    from ctypes import wintypes  # noqa: PLC0415

    user32, kernel32 = _win_api()
    GW_OWNER, GWL_EXSTYLE, WS_EX_TOOLWINDOW = 4, -20, 0x00000080
    PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
    found: List[Window] = []

    def process_name(hwnd) -> str:
        pid = wintypes.DWORD()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        h = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid.value)
        if not h:
            return ""
        try:
            buf = ctypes.create_unicode_buffer(1024)
            size = wintypes.DWORD(len(buf))
            if kernel32.QueryFullProcessImageNameW(h, 0, buf, ctypes.byref(size)):
                return Path(buf.value).stem.lower()
            return ""
        finally:
            kernel32.CloseHandle(h)

    @ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
    def callback(hwnd, _):
        if not user32.IsWindowVisible(hwnd) or user32.GetWindow(hwnd, GW_OWNER):
            return True
        if user32.GetWindowLongW(hwnd, GWL_EXSTYLE) & WS_EX_TOOLWINDOW:
            return True
        length = user32.GetWindowTextLengthW(hwnd)
        if length == 0:
            return True
        buf = ctypes.create_unicode_buffer(length + 1)
        user32.GetWindowTextW(hwnd, buf, length + 1)
        title = buf.value.strip()
        if title and normalize(title) not in _SKIP_TITLES:
            found.append(Window(hwnd, title, process_name(hwnd)))
        return True

    user32.EnumWindows(callback, 0)  # o callback já tem o protótipo certo (WINFUNCTYPE)
    return found


def _win_action(hwnd, action: str) -> None:
    user32, _ = _win_api()
    WM_CLOSE, SW_MINIMIZE, SW_MAXIMIZE, SW_RESTORE = 0x0010, 6, 3, 9
    if action == "close":
        # Mesmo efeito do "X": o programa ainda pode perguntar se quer salvar.
        user32.PostMessageW(hwnd, WM_CLOSE, 0, 0)
    elif action == "minimize":
        user32.ShowWindow(hwnd, SW_MINIMIZE)
    elif action == "maximize":
        user32.ShowWindow(hwnd, SW_MAXIMIZE)
    elif action == "focus":
        if user32.IsIconic(hwnd):
            user32.ShowWindow(hwnd, SW_RESTORE)
        user32.SetForegroundWindow(hwnd)


def match_windows(query: str, windows: List[Window]) -> List[Window]:
    """Janelas cujo título ou programa combinam com o pedido ('chrome', 'spotify', 'relatório.docx')."""
    q = normalize(query)
    if not q:
        return []
    tokens = q.split()
    hits = []
    for w in windows:
        hay = normalize(f"{w.title} {w.process}")
        if q in hay or all(t in hay.split() for t in tokens):
            hits.append(w)
    return hits


def tool_list_windows(_: Dict[str, Any]) -> str:
    windows = list_windows()
    if not windows:
        return "Nenhuma janela aberta."
    lines = [f"- {w.title} ({w.process})" if w.process else f"- {w.title}" for w in windows[:40]]
    return f"{len(windows)} janelas abertas:\n" + "\n".join(lines)


ACTION_PT = {"close": "Fechei", "minimize": "Minimizei", "maximize": "Maximizei", "focus": "Trouxe para a frente"}


def tool_window(inp: Dict[str, Any]) -> str:
    action = str(inp.get("action", ""))
    if action not in WINDOW_ACTIONS:
        raise ToolError(f"ação inválida: {action}")
    query = str(inp.get("name", "")).strip()
    apply_all = bool(inp.get("all_matches", False))
    windows = list_windows()
    hits = match_windows(query, windows)
    if not hits:
        names = ", ".join(sorted({w.process or w.title for w in windows})[:20])
        raise ToolError(f"nenhuma janela aberta combina com '{query}'. Abertas: {names or 'nenhuma'}")
    if len(hits) > 1 and not apply_all and action != "close":
        hits = hits[:1]  # minimizar/focar várias de uma vez raramente é o desejado
    if len(hits) > 1 and not apply_all:
        titles = "; ".join(w.title for w in hits[:8])
        raise ToolError(f"há {len(hits)} janelas que combinam: {titles}. Chame de novo com um nome mais "
                        "específico, ou com all_matches=true para fechar todas.")
    for w in hits:
        window_action(w, action)
    verb = ACTION_PT[action]
    return f"{verb} {len(hits)} janela(s): " + "; ".join(w.title for w in hits[:8]) + "."


def tool_system_status(_: Dict[str, Any]) -> str:
    disk = shutil.disk_usage(Path.home())
    parts = [
        f"Computador {socket.gethostname()} com {platform.system()} {platform.release()}",
        f"{os.cpu_count()} núcleos",
        f"disco: {disk.free / 1e9:.0f} GB livres de {disk.total / 1e9:.0f} GB",
    ]
    if hasattr(os, "getloadavg"):
        parts.append(f"carga média 1 min: {os.getloadavg()[0]:.2f}")
    up = uptime_seconds()
    if up is not None:
        parts.append(f"ligado há {int(up // 3600)} h {int(up % 3600 // 60)} min")
    bat = battery_percent()
    if bat:
        parts.append(f"bateria {bat}")
    parts.append("hora do PC: " + time.strftime("%H:%M"))
    return "; ".join(parts) + "."


def build_tools(cfg: Dict[str, Any]) -> Dict[str, Tool]:
    tools: List[Tool] = [
        Tool(
            "pc_open_url", "Abrindo no navegador do PC",
            "Abre um endereço http(s) no navegador padrão do computador do Breno. Para pesquisas, "
            "monte a URL de busca (ex.: https://www.google.com/search?q=...). Para abrir programas "
            "instalados, use pc_open_app em vez desta.",
            obj_schema({"url": {"type": "string", "description": "URL completa começando com http:// ou https://"}}),
            tool_open_url,
        ),
        Tool(
            "pc_media", "Controlando a mídia do PC",
            "Controla a reprodução de mídia e o volume do computador.",
            obj_schema({"action": {"type": "string", "enum": MEDIA_ACTIONS,
                                   "description": "play_pause, next, previous, volume_up, volume_down ou mute"}}),
            tool_media,
        ),
        Tool(
            "pc_lock_screen", "Bloqueando o PC",
            "Bloqueia a tela do computador.",
            obj_schema({}),
            tool_lock_screen,
        ),
        Tool(
            "pc_clipboard", "Copiando para o PC",
            "Coloca um texto na área de transferência do computador, para o Breno colar lá.",
            obj_schema({"text": {"type": "string", "description": "Texto a copiar"}}),
            tool_clipboard,
        ),
        Tool(
            "pc_system_status", "Consultando o PC",
            "Informa o estado do computador: sistema, disco livre, tempo ligado, bateria e hora.",
            obj_schema({}),
            tool_system_status,
        ),
        Tool(
            "pc_list_windows", "Vendo as janelas do PC",
            "Lista as janelas abertas no computador (título e programa). Use antes de fechar/minimizar "
            "quando não tiver certeza do nome.",
            obj_schema({}),
            tool_list_windows,
        ),
        Tool(
            "pc_window", "Mexendo nas janelas do PC",
            "Fecha, minimiza, maximiza ou traz para a frente janelas abertas no computador, pelo nome do "
            "programa ou parte do título (ex.: 'chrome', 'spotify', 'relatório'). Fechar equivale a clicar "
            "no X: o programa pode pedir para salvar. Se várias janelas combinarem, fechar exige "
            "all_matches=true ou um nome mais específico.",
            obj_schema({
                "name": {"type": "string", "description": "Programa ou parte do título da janela"},
                "action": {"type": "string", "enum": WINDOW_ACTIONS,
                           "description": "close, minimize, maximize ou focus"},
                "all_matches": {"type": "boolean",
                                "description": "true para aplicar em todas as janelas que combinarem"},
            }),
            tool_window,
        ),
    ]

    manual: Dict[str, str] = cfg.get("apps") or {}
    installed: Dict[str, Launcher] = discover_installed_apps() if cfg.get("auto_apps", True) else {}
    cache = {"apps": installed, "at": time.time()}

    def installed_now() -> Dict[str, Launcher]:
        # Reescaneia de vez em quando, para achar programas instalados depois que o agente subiu.
        if cfg.get("auto_apps", True) and time.time() - cache["at"] > 600:
            cache["apps"], cache["at"] = discover_installed_apps(), time.time()
        return cache["apps"]

    names = sorted(set(manual) | set(installed), key=str.lower)
    if names:
        listing = ", ".join(names[:300]) + (" …" if len(names) > 300 else "")
        tools.append(Tool(
            "pc_open_app", "Abrindo programa no PC",
            "Abre um programa instalado no computador (não use o navegador para isso). "
            "Passe o nome do programa; pode ser aproximado. Programas instalados: " + listing + ".",
            obj_schema({"name": {"type": "string", "description": "Nome do programa, ex.: Spotify, Google Chrome"}}),
            make_open_app(manual, installed_now),
        ))

    shortcuts: Dict[str, Dict[str, str]] = cfg.get("shortcuts") or {}
    if shortcuts:
        listing = "; ".join(f"{n}: {s.get('description', '')}".strip() for n, s in sorted(shortcuts.items()))
        tools.append(Tool(
            "pc_run_shortcut", "Executando atalho no PC",
            "Executa um atalho pré-configurado pelo Breno no computador e devolve a saída. "
            "Atalhos disponíveis -> " + listing,
            obj_schema({"name": {"type": "string", "enum": sorted(shortcuts), "description": "Nome do atalho"}}),
            make_run_shortcut(shortcuts),
        ))

    return {t.name: t for t in tools}


# --------------------------------------------------------------------------
# Servidor HTTP
# --------------------------------------------------------------------------

def make_handler(token: str, tools: Dict[str, Tool]):
    expected = f"Bearer {token}".encode("utf-8")

    class Handler(BaseHTTPRequestHandler):
        server_version = "JarvisAgent/2"

        def log_message(self, fmt: str, *args: Any) -> None:  # log curto, sem headers (o token não aparece)
            sys.stderr.write(f"[jarvis] {self.address_string()} {fmt % args}\n")

        def _send(self, code: int, payload: Dict[str, Any]) -> None:
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _authorized(self) -> bool:
            got = self.headers.get("Authorization", "").encode("utf-8")
            if hmac.compare_digest(got, expected):
                return True
            self._send(401, {"ok": False, "error": "não autorizado"})
            return False

        def do_GET(self) -> None:  # noqa: N802
            if not self._authorized():
                return
            if self.path == "/health":
                self._send(200, {"ok": True})
            elif self.path == "/tools":
                self._send(200, {"tools": [t.describe() for t in tools.values()]})
            else:
                self._send(404, {"ok": False, "error": "rota inexistente"})

        def do_POST(self) -> None:  # noqa: N802
            if not self._authorized():
                return
            if not self.path.startswith("/tools/"):
                self._send(404, {"ok": False, "error": "rota inexistente"})
                return
            tool = tools.get(self.path[len("/tools/"):])
            if tool is None:
                self._send(404, {"ok": False, "error": "ferramenta inexistente"})
                return

            length = int(self.headers.get("Content-Length") or 0)
            if length > MAX_BODY:
                self._send(413, {"ok": False, "error": "requisição grande demais"})
                return
            try:
                body = json.loads(self.rfile.read(length) or b"{}")
                inp = body.get("input") or {}
                if not isinstance(inp, dict):
                    raise ValueError
            except (ValueError, AttributeError):
                self._send(400, {"ok": False, "error": "JSON inválido"})
                return

            try:
                result = tool.handler(inp)
                self._send(200, {"ok": True, "result": result})
            except ToolError as e:
                self._send(200, {"ok": False, "error": str(e)})
            except Exception as e:  # noqa: BLE001 - não derruba o servidor
                self._send(200, {"ok": False, "error": f"erro inesperado: {type(e).__name__}: {e}"})

    return Handler


def create_server(cfg: Dict[str, Any]) -> ThreadingHTTPServer:
    tools = build_tools(cfg)
    server = ThreadingHTTPServer((cfg["host"], int(cfg["port"])), make_handler(str(cfg["token"]), tools))
    server.daemon_threads = True
    return server


# --------------------------------------------------------------------------
# Descoberta na rede (UDP)
# --------------------------------------------------------------------------

def discovery_reply(cfg: Dict[str, Any]) -> bytes:
    return json.dumps(
        {"service": "jarvis", "port": int(cfg["port"]), "name": socket.gethostname()},
        ensure_ascii=False,
    ).encode("utf-8")


def start_discovery(cfg: Dict[str, Any], http_port: int) -> Optional[socket.socket]:
    """Responde aos broadcasts do app numa thread. Devolve o socket (para fechar nos testes)."""
    port = int(cfg.get("discovery_port", 8766))
    if port <= 0:
        return None
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        sock.bind((cfg.get("host", "0.0.0.0"), port))
    except OSError as e:
        print(f"[jarvis] Descoberta desligada (porta UDP {port} ocupada: {e}).")
        sock.close()
        return None
    reply = discovery_reply({**cfg, "port": http_port})

    def loop() -> None:
        while True:
            try:
                data, addr = sock.recvfrom(512)
            except OSError:
                return  # socket fechado
            if data.strip() == DISCOVERY_MAGIC:
                try:
                    sock.sendto(reply, addr)
                except OSError:
                    pass

    threading.Thread(target=loop, name="jarvis-discovery", daemon=True).start()
    return sock


# --------------------------------------------------------------------------
# Iniciar com o sistema
# --------------------------------------------------------------------------

def _pythonw() -> str:
    exe = Path(sys.executable)
    candidate = exe.with_name("pythonw.exe")
    return str(candidate if candidate.exists() else exe)


def autostart_path() -> Path:
    if SYSTEM == "Windows":
        return (Path(os.environ["APPDATA"]) / "Microsoft" / "Windows" / "Start Menu" / "Programs"
                / "Startup" / "JarvisAgent.vbs")
    if SYSTEM == "Darwin":
        return Path.home() / "Library" / "LaunchAgents" / f"{AUTOSTART_ID}.plist"
    return Path.home() / ".config" / "systemd" / "user" / "jarvis-agent.service"


def install_autostart(config_path: Path) -> None:
    script = Path(__file__).resolve()
    config_path = config_path.resolve()
    load_config(config_path)  # garante que o config.json (e o token) existem
    target = autostart_path()
    target.parent.mkdir(parents=True, exist_ok=True)

    if SYSTEM == "Windows":
        # .vbs na pasta Inicializar: roda o pythonw sem janela de console.
        cmd = f'""{_pythonw()}"" ""{script}"" --config ""{config_path}""'
        target.write_text(f'CreateObject("WScript.Shell").Run "{cmd}", 0, False\r\n', encoding="utf-8")
        print(f"[jarvis] Pronto: o agente vai iniciar quando você entrar no Windows ({target}).")
        print("[jarvis] Para iniciar agora sem reiniciar, dê dois cliques nesse arquivo.")
    elif SYSTEM == "Darwin":
        log = Path.home() / "Library" / "Logs" / "jarvis-agent.log"
        target.write_text(f"""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>{AUTOSTART_ID}</string>
  <key>ProgramArguments</key><array>
    <string>{sys.executable}</string><string>{script}</string>
    <string>--config</string><string>{config_path}</string>
  </array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>{log}</string>
  <key>StandardErrorPath</key><string>{log}</string>
</dict></plist>
""", encoding="utf-8")
        subprocess.run(["launchctl", "unload", str(target)], capture_output=True)
        run(["launchctl", "load", "-w", str(target)])
        print(f"[jarvis] Pronto: agente rodando e configurado para iniciar no login ({target}).")
    else:
        target.write_text(f"""[Unit]
Description=Agente do Jarvis
After=network-online.target

[Service]
ExecStart="{sys.executable}" "{script}" --config "{config_path}"
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
""", encoding="utf-8")
        run(["systemctl", "--user", "daemon-reload"])
        run(["systemctl", "--user", "enable", "--now", "jarvis-agent.service"])
        print(f"[jarvis] Pronto: serviço systemd do usuário ativo ({target}).")
        print("[jarvis] Logs: journalctl --user -u jarvis-agent -f")


def uninstall_autostart() -> None:
    target = autostart_path()
    if SYSTEM == "Darwin" and target.exists():
        subprocess.run(["launchctl", "unload", "-w", str(target)], capture_output=True)
    elif SYSTEM == "Linux" and target.exists():
        subprocess.run(["systemctl", "--user", "disable", "--now", "jarvis-agent.service"], capture_output=True)
    if target.exists():
        target.unlink()
        print(f"[jarvis] Início automático removido ({target}).")
    else:
        print("[jarvis] O início automático não estava instalado.")


def main() -> None:
    parser = argparse.ArgumentParser(description="Agente do Jarvis no PC")
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG_PATH)
    parser.add_argument("--install-autostart", action="store_true", help="iniciar com o sistema")
    parser.add_argument("--uninstall-autostart", action="store_true", help="parar de iniciar com o sistema")
    args = parser.parse_args()

    if args.install_autostart:
        install_autostart(args.config)
        return
    if args.uninstall_autostart:
        uninstall_autostart()
        return

    cfg = load_config(args.config)
    server = create_server(cfg)
    port = server.server_address[1]
    discovery = start_discovery(cfg, port)
    names = ", ".join(build_tools(cfg))
    print(f"[jarvis] Agente no ar em http://{lan_ip()}:{port}  ({SYSTEM})")
    print(f"[jarvis] Ferramentas: {names}")
    if discovery:
        print(f"[jarvis] Descoberta na rede ativa (UDP {cfg.get('discovery_port', 8766)}).")
    # Só mostra o token num terminal interativo (nunca em arquivo de log do início automático).
    if sys.stdout.isatty():
        print("[jarvis] No app: Ajustes → Token do PC →", cfg["token"])
    else:
        print(f"[jarvis] Token: veja o campo 'token' em {args.config.resolve()}")
    print("[jarvis] Ctrl+C para sair.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[jarvis] Até logo, senhor.")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
