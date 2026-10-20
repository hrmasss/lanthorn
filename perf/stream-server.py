#!/usr/bin/env python3
"""Local stream server for network playback checks (P1.8). Serves a directory over HTTP and HTTPS and
logs every request's path and User-Agent, so US-61.5 can be checked from the log.

    perf/stream-server.py <dir> <log> [http_port=8000] [https_port=8443]

From the emulator the host is 10.0.2.2. HTTPS uses a throwaway self-signed certificate made in <dir>
(mpv does not verify TLS by default). /slow/<file> waits 30 s before answering, for timeout checks.
"""
import http.server
import os
import ssl
import subprocess
import sys
import threading
import time

root, log_path = sys.argv[1], sys.argv[2]
http_port = int(sys.argv[3]) if len(sys.argv) > 3 else 8000
https_port = int(sys.argv[4]) if len(sys.argv) > 4 else 8443
log = open(log_path, "a", buffering=1)


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=root, **kw)

    def log_message(self, fmt, *args):
        pass

    def _note(self):
        log.write(f"{time.strftime('%H:%M:%S')} {self.command} {self.path} UA={self.headers.get('User-Agent')!r}\n")

    def do_GET(self):
        self._note()
        if self.path.startswith("/slow/"):
            time.sleep(30)
            self.path = self.path[len("/slow"):]
        super().do_GET()

    def do_HEAD(self):
        self._note()
        super().do_HEAD()


def serve(port, tls):
    server = http.server.ThreadingHTTPServer(("0.0.0.0", port), Handler)
    if tls:
        cert = os.path.join(root, ".cert.pem")
        key = os.path.join(root, ".key.pem")
        if not os.path.exists(cert):
            subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
                            "-subj", "/CN=10.0.2.2", "-keyout", key, "-out", cert], check=True,
                           capture_output=True)
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(cert, key)
        server.socket = ctx.wrap_socket(server.socket, server_side=True)
    server.serve_forever()


threading.Thread(target=serve, args=(https_port, True), daemon=True).start()
serve(http_port, False)
