"""Testes do agente do PC: python -m unittest test_jarvis_agent.py"""

import json
import sys
import threading
import unittest
import urllib.error
import urllib.request
from unittest import mock

import jarvis_agent as ja

TOKEN = "segredo-de-teste"
CFG = {
    "host": "127.0.0.1",
    "port": 0,
    "token": TOKEN,
    "auto_apps": False,  # testes não dependem dos programas desta máquina
    "apps": {"editor": "true"},
    "shortcuts": {
        "eco": {"description": "imprime ola", "command": f'"{sys.executable}" -c "print(\'olá\')"'},
        "falha": {"description": "sai com erro", "command": f'"{sys.executable}" -c "import sys; sys.exit(3)"'},
    },
}


class AgentServerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ja.create_server(CFG)
        cls.base = f"http://127.0.0.1:{cls.server.server_address[1]}"
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def call(self, path, body=None, token=TOKEN):
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(self.base + path, data=data, method="POST" if data else "GET")
        if token:
            req.add_header("Authorization", f"Bearer {token}")
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read())

    def test_sem_token_e_401(self):
        self.assertEqual(self.call("/tools", token=None)[0], 401)
        self.assertEqual(self.call("/tools", token="errado")[0], 401)

    def test_lista_ferramentas_com_enums_do_config(self):
        code, body = self.call("/tools")
        self.assertEqual(code, 200)
        tools = {t["name"]: t for t in body["tools"]}
        self.assertIn("pc_open_url", tools)
        self.assertIn("editor", tools["pc_open_app"]["description"])
        self.assertEqual(tools["pc_run_shortcut"]["input_schema"]["properties"]["name"]["enum"], ["eco", "falha"])
        for t in tools.values():
            self.assertRegex(t["name"], r"^[a-zA-Z0-9_-]{1,64}$")
            self.assertEqual(t["input_schema"]["type"], "object")
            self.assertTrue(t["label"])

    def test_atalho_devolve_saida(self):
        code, body = self.call("/tools/pc_run_shortcut", {"input": {"name": "eco"}})
        self.assertEqual(code, 200)
        self.assertEqual(body, {"ok": True, "result": "olá"})

    def test_atalho_com_erro_e_desconhecido(self):
        _, body = self.call("/tools/pc_run_shortcut", {"input": {"name": "falha"}})
        self.assertFalse(body["ok"])
        self.assertIn("3", body["error"])
        _, body = self.call("/tools/pc_run_shortcut", {"input": {"name": "rm -rf /"}})
        self.assertFalse(body["ok"])

    def test_url_so_http(self):
        _, body = self.call("/tools/pc_open_url", {"input": {"url": "file:///etc/passwd"}})
        self.assertFalse(body["ok"])
        with mock.patch.object(ja.webbrowser, "open", return_value=True) as op:
            _, body = self.call("/tools/pc_open_url", {"input": {"url": "https://example.com"}})
        self.assertTrue(body["ok"])
        op.assert_called_once_with("https://example.com")

    def test_status_do_sistema(self):
        _, body = self.call("/tools/pc_system_status", {"input": {}})
        self.assertTrue(body["ok"], body)
        self.assertIn("disco", body["result"])

    def test_ferramenta_inexistente_e_json_invalido(self):
        self.assertEqual(self.call("/tools/nao_existe", {"input": {}})[0], 404)
        req = urllib.request.Request(self.base + "/tools/pc_open_url", data=b"{quebrado", method="POST")
        req.add_header("Authorization", f"Bearer {TOKEN}")
        with self.assertRaises(urllib.error.HTTPError) as ctx:
            urllib.request.urlopen(req, timeout=10)
        self.assertEqual(ctx.exception.code, 400)


class OpenAppTest(unittest.TestCase):
    NAMES = ["Spotify", "Spotify Widget", "Google Chrome", "Visual Studio Code", "Calculadora",
             "Microsoft Edge", "WhatsApp", "Configurações", "Desinstalar Algo"]

    def test_normalize(self):
        self.assertEqual(ja.normalize("  Configurações® "), "configuracoes")

    def test_find_apps(self):
        self.assertEqual(ja.find_apps("spotify", self.NAMES)[0], "Spotify")
        self.assertEqual(ja.find_apps("chrome", self.NAMES), ["Google Chrome"])
        self.assertEqual(ja.find_apps("vs code", self.NAMES), [])
        self.assertEqual(ja.find_apps("visual studio", self.NAMES), ["Visual Studio Code"])
        self.assertEqual(ja.find_apps("configuracoes", self.NAMES), ["Configurações"])
        self.assertEqual(ja.find_apps("photoshop", self.NAMES), [])

    def test_handler_abre_o_mais_provavel_e_reclama_se_ambiguo(self):
        opened = []
        installed = {n: (lambda n=n: opened.append(n)) for n in self.NAMES}
        handler = ja.make_open_app({}, lambda: installed)
        self.assertEqual(handler({"name": "spotify"}), "Abrindo Spotify.")
        self.assertEqual(handler({"name": "whatsapp"}), "Abrindo WhatsApp.")
        self.assertEqual(opened, ["Spotify", "WhatsApp"])
        with self.assertRaises(ja.ToolError):
            handler({"name": "photoshop"})

    def test_descoberta_no_windows_le_get_startapps_e_filtra_lixo(self):
        fake = json.dumps([
            {"Name": "Spotify", "AppID": "SpotifyAB.SpotifyMusic_zpdnekdrzrea0!Spotify"},
            {"Name": "Google Chrome", "AppID": "Chrome"},
            {"Name": "Desinstalar o Programa X", "AppID": "X-uninstall"},
        ])
        launched = []
        with mock.patch.object(ja, "SYSTEM", "Windows"), \
             mock.patch.object(ja, "run", return_value=fake), \
             mock.patch.object(ja, "_popen", side_effect=launched.append):
            apps = ja.discover_installed_apps()
            self.assertEqual(sorted(apps), ["Google Chrome", "Spotify"])
            apps["Spotify"]()
        self.assertEqual(launched, [["explorer.exe", "shell:AppsFolder\\SpotifyAB.SpotifyMusic_zpdnekdrzrea0!Spotify"]])

    def test_descoberta_falha_sem_derrubar(self):
        with mock.patch.object(ja, "SYSTEM", "Windows"), \
             mock.patch.object(ja, "run", side_effect=ja.ToolError("sem powershell")):
            self.assertEqual(ja.discover_installed_apps(), {})


class DiscoveryTest(unittest.TestCase):
    def test_responde_ao_broadcast_sem_vazar_token(self):
        import socket
        cfg = dict(CFG, discovery_port=0)
        # porta 0 desliga; usa uma porta livre qualquer
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
        probe.close()
        cfg["discovery_port"] = port
        sock = ja.start_discovery(cfg, http_port=8765)
        self.assertIsNotNone(sock)
        try:
            client = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            client.settimeout(3)
            client.sendto(b"lixo", ("127.0.0.1", port))
            client.sendto(ja.DISCOVERY_MAGIC, ("127.0.0.1", port))
            data, _ = client.recvfrom(1024)
            reply = json.loads(data)
            self.assertEqual(reply["service"], "jarvis")
            self.assertEqual(reply["port"], 8765)
            self.assertNotIn(TOKEN, data.decode())
            client.close()
        finally:
            sock.close()

    def test_porta_zero_desliga_descoberta(self):
        self.assertIsNone(ja.start_discovery(dict(CFG, discovery_port=0), http_port=8765))


if __name__ == "__main__":
    unittest.main()
