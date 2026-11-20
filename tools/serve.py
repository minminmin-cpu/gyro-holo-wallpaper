"""本地预览服务：默认 HTTPS 自签证书（陀螺仪需要安全上下文）。
用法:
  python tools/serve.py                 # HTTPS 0.0.0.0:8443
  python tools/serve.py --http --port 4173
  python tools/serve.py --https --port 8443
手机真机调试更稳的做法：adb reverse tcp:8443 tcp:8443，然后访问 http://localhost:8443
（localhost 属于安全上下文，可绕开自签证书信任问题）。
"""
from __future__ import annotations

import argparse
import functools
import http.server
import shutil
import socket
import ssl
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CERT_DIR = ROOT / ".cert"


# Windows 注册表常把 .js 映射成 text/plain，浏览器会因严格 MIME 检查
# 拒绝执行 <script type="module">，导致页面停在初始遮罩。这里显式固定。
class HoloHandler(http.server.SimpleHTTPRequestHandler):
    extensions_map = {
        **http.server.SimpleHTTPRequestHandler.extensions_map,
        ".js": "text/javascript",
        ".mjs": "text/javascript",
        ".html": "text/html",
        ".css": "text/css",
        ".json": "application/json",
        ".png": "image/png",
        ".gif": "image/gif",
    }

    def log_message(self, *args):
        pass


def lan_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("223.5.5.5", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except OSError:
        return socket.gethostbyname(socket.gethostname())


def ensure_cert(ip: str) -> tuple[Path, Path]:
    CERT_DIR.mkdir(exist_ok=True)
    key, crt = CERT_DIR / "key.pem", CERT_DIR / "cert.pem"
    if key.exists() and crt.exists():
        return crt, key
    exe = shutil.which("openssl")
    if not exe:
        raise RuntimeError("未找到 openssl，请改用 --http 或自行准备证书")
    cmd = [
        exe, "req", "-x509", "-newkey", "rsa:2048", "-nodes",
        "-keyout", str(key), "-out", str(crt), "-days", "365",
        "-subj", "/CN=holo-wallpaper",
        "-addext", f"subjectAltName=DNS:localhost,IP:127.0.0.1,IP:{ip}",
    ]
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    print("已生成自签证书 ->", CERT_DIR)
    return crt, key


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=None)
    ap.add_argument("--host", default="0.0.0.0")
    mode = ap.add_mutually_exclusive_group()
    mode.add_argument("--https", action="store_true")
    mode.add_argument("--http", action="store_true")
    args = ap.parse_args()
    use_https = not args.http
    port = args.port or (8443 if use_https else 4173)
    ip = lan_ip()

    handler = functools.partial(HoloHandler, directory=str(ROOT))
    httpd = http.server.ThreadingHTTPServer((args.host, port), handler)

    scheme = "http"
    if use_https:
        crt, key = ensure_cert(ip)
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(str(crt), str(key))
        httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
        scheme = "https"

    print()
    print(f"  桌面预览   {scheme}://localhost:{port}/")
    print(f"  手机访问   {scheme}://{ip}:{port}/")
    print(f"  单文件版   {scheme}://localhost:{port}/standalone.html")
    if use_https:
        print("  注意：手机需信任 .cert/cert.pem；iOS 建议改用 adb reverse + localhost 或部署到公网 HTTPS。")
    print("  调试参数   ?debug=1&autostart=1&view=0.4,0.2")
    print("  Ctrl+C 停止")
    print()
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止")
    finally:
        httpd.server_close()


if __name__ == "__main__":
    main()
