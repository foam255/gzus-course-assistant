from __future__ import annotations

import base64
import json
import subprocess
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from app import create_app
from jwglxt import (
    CAS_KEY_SIZE,
    CAS_MODULUS_HEX,
    CAS_PUBLIC_EXPONENT,
    CaptchaRejected,
    JwglxtClient,
    LoginRejected,
    SessionExpired,
    Term,
    _cas_rsa_encrypt,
    _parse_weeks,
    _rsa_encrypt_password,
)

CAS_PRIVATE_EXPONENT_HEX = (
    "413798867d69babed22e0dd3d4031c635f3e9dbca0fa50a32974a0e230787b7f"
    "7ba78caefbee828a051c690357a8cc31dba8efc738b4db22e887571ef1ec5a5a"
    "55b6d866f6a67527f6a7d78a127c9f687008bb540228b50aa2d1ca5a4ff71107"
    "234f936b611ac46432a26da9c302eaa7180820df70593353b3f8c0247fe97a45"
)


class MockState:
    def __init__(
        self,
        key_path: Path,
        modulus_b64: str,
        password: str,
        cas_expiry: bool = False,
        direct_ticket: bool = True,
    ) -> None:
        self.key_path = key_path
        self.modulus_b64 = modulus_b64
        self.password = password
        self.cas_expiry = cas_expiry
        self.direct_ticket = direct_ticket
        self.captcha_id = "captcha-test-id"
        self.captcha_code = "7"
        self.tgt = "TGT-test"
        self.ticket = "ST-test"

    def decrypt_password(self, value: str) -> str:
        with tempfile.NamedTemporaryFile(delete=False) as cipher_file:
            cipher_file.write(base64.b64decode(value))
            cipher_path = Path(cipher_file.name)
        try:
            return subprocess.check_output(
                [
                    "openssl",
                    "pkeyutl",
                    "-decrypt",
                    "-inkey",
                    str(self.key_path),
                    "-pkeyopt",
                    "rsa_padding_mode:pkcs1",
                    "-in",
                    str(cipher_path),
                ],
                stderr=subprocess.DEVNULL,
            ).decode("utf-8")
        finally:
            cipher_path.unlink(missing_ok=True)

    def decrypt_cas_password(self, value: str) -> str:
        modulus = int(CAS_MODULUS_HEX, 16)
        private_exponent = int(CAS_PRIVATE_EXPONENT_HEX, 16)
        plaintext = bytearray()
        for part in value.split():
            decrypted = pow(int(part, 16), private_exponent, modulus)
            plaintext.extend(decrypted.to_bytes(CAS_KEY_SIZE, "little"))
        return bytes(plaintext).rstrip(b"\x00").decode("latin-1")


class MockHandler(BaseHTTPRequestHandler):
    server_version = "MockJwglxt/1.0"

    def log_message(self, format_string, *args):
        return

    @property
    def state(self) -> MockState:
        return self.server.state

    def _send(self, status: int, body: bytes, content_type: str) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authed(self) -> bool:
        cookie = self.headers.get("Cookie", "")
        return "JSESSIONID=mock-session" in cookie

    def _redirect_login(self) -> None:
        self.send_response(302)
        target = "/cas/lyuapServer/login" if self.state.cas_expiry else (
            "/jwglxt/xtgl/login_slogin.html"
        )
        self.send_header("Location", target)
        self.end_headers()

    def do_GET(self) -> None:
        path = urlparse(self.path).path
        if path == "/lyuapServer/login":
            self._send(200, b"<html>CAS login</html>", "text/html; charset=utf-8")
            return

        if path == "/lyuapServer/loginType":
            body = json.dumps(
                {
                    "meta": {
                        "success": True,
                        "statusCode": 200,
                        "message": "ok",
                    },
                    "data": {
                        "isVerifyCode": "1",
                        "defaultLoginUrl": "https://ehall.gzus.edu.cn",
                    },
                }
            ).encode()
            self._send(200, body, "application/json; charset=utf-8")
            return

        if path == "/lyuapServer/kaptcha":
            body = json.dumps(
                {
                    "kaptchaType": "1",
                    "uid": self.state.captcha_id,
                    "content": (
                        "data:image/png;base64,"
                        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwC"
                        "AAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
                    ),
                    "timeout": 300,
                }
            ).encode()
            self._send(200, body, "application/json; charset=utf-8")
            return

        if path == "/sso/lyiotlogin":
            ticket = parse_qs(urlparse(self.path).query).get("ticket", [""])[0]
            if ticket != self.state.ticket:
                self._send(400, b"invalid ticket", "text/plain")
                return
            self.send_response(302)
            self.send_header("Set-Cookie", "JSESSIONID=mock-session; Path=/")
            self.send_header(
                "Location",
                "/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html",
            )
            self.end_headers()
            return

        if path == "/jwglxt/xtgl/login_slogin.html":
            body = """
            <form action="/jwglxt/xtgl/login_slogin.html" method="post">
              <input type="hidden" id="csrftoken" name="csrftoken" value="csrf-test">
              <input type="hidden" id="mmsfjm" value="1">
              <input type="hidden" id="dlsfbxyzm" value="0">
              <input id="yhm" name="yhm">
              <input id="mm" name="mm" type="password">
            </form>
            """.encode()
            self._send(200, body, "text/html; charset=utf-8")
            return

        if path == "/jwglxt/xtgl/login_getPublicKey.html":
            body = json.dumps(
                {
                    "modulus": self.state.modulus_b64,
                    "exponent": "AQAB",
                }
            ).encode()
            self._send(200, body, "application/json; charset=utf-8")
            return

        if path == "/cas/lyuapServer/login":
            body = "<html><title>统一身份认证平台</title></html>".encode("utf-8")
            self._send(200, body, "text/html; charset=utf-8")
            return

        if path == "/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html":
            if not self._authed():
                self._redirect_login()
                return
            body = b"""
            <html><body>
              <div id="xsmc">2026001</div>
              <select name="xnm">
                <option value="2025">2025-2026</option>
                <option value="2026" selected>2026-2027</option>
              </select>
              <select name="xqm">
                <option value="3" selected>1</option>
                <option value="12">2</option>
              </select>
            </body></html>
            """
            self._send(200, body, "text/html; charset=utf-8")
            return

        self._send(404, b"not found", "text/plain")

    def do_POST(self) -> None:
        path = urlparse(self.path).path
        length = int(self.headers.get("Content-Length", "0"))
        form = parse_qs(self.rfile.read(length).decode("utf-8"))

        if path == "/lyuapServer/v1/tickets":
            username = form.get("username", [""])[0]
            code = form.get("code", [""])[0]
            captcha_id = form.get("id", [""])[0]
            if code != self.state.captcha_code or captcha_id != self.state.captcha_id:
                body = json.dumps(
                    {
                        "meta": {
                            "success": True,
                            "statusCode": 200,
                            "message": "ok",
                        },
                        "data": {"code": "CODEFALSE"},
                    }
                ).encode()
                self._send(200, body, "application/json; charset=utf-8")
                return

            try:
                password = self.state.decrypt_cas_password(
                    form.get("password", [""])[0]
                )
            except Exception:
                password = ""
            if username != "2026001" or password != self.state.password:
                body = json.dumps(
                    {
                        "meta": {
                            "success": True,
                            "statusCode": 200,
                            "message": "ok",
                        },
                        "data": {"code": "FALSE"},
                    }
                ).encode()
                self._send(200, body, "application/json; charset=utf-8")
                return

            payload = {
                "meta": {
                    "success": True,
                    "statusCode": 200,
                    "message": "ok",
                },
                "tgt": self.state.tgt,
            }
            if self.state.direct_ticket:
                payload["ticket"] = self.state.ticket
            body = json.dumps(payload).encode()
            self._send(200, body, "application/json; charset=utf-8")
            return

        if path == f"/lyuapServer/v1/tickets/{self.state.tgt}":
            body = json.dumps(self.state.ticket).encode()
            self._send(200, body, "application/json; charset=utf-8")
            return

        if path == "/jwglxt/xtgl/login_slogin.html":
            password = ""
            try:
                password = self.state.decrypt_password(form.get("mm", [""])[0])
            except Exception:
                pass
            if form.get("csrftoken", [""])[0] != "csrf-test" or password != self.state.password:
                body = '<p id="tips">用户名或密码不正确</p>'.encode("utf-8")
                self._send(200, body, "text/html; charset=utf-8")
                return
            self.send_response(302)
            self.send_header("Set-Cookie", "JSESSIONID=mock-session; Path=/")
            self.send_header("Location", "/jwglxt/xtgl/index_initMenu.html")
            self.end_headers()
            return

        if path == "/jwglxt/kbcx/xskbcx_cxXsgrkb.html":
            if not self._authed():
                self._redirect_login()
                return
            if form.get("kzlx", [""])[0] != "ck":
                self._send(400, b"invalid kzlx", "text/plain")
                return
            body = json.dumps(
                {
                    "xsxx": {"XM": "测试同学"},
                    "kbList": [
                        {
                            "kcmc": "高等数学",
                            "xqj": "1",
                            "jcs": "1-2",
                            "cdmc": "A101",
                            "xm": "张老师",
                            "zcmc": "1-16周",
                            "xf": "4",
                            "xqh_id": "1",
                        },
                        {
                            "kcmc": "大学英语",
                            "xqj": "3",
                            "jcs": "3-4",
                            "cdmc": "B203",
                            "xm": "李老师",
                            "zcmc": "1-16周(单)",
                            "xf": "3",
                            "xqh_id": "1",
                        },
                        {
                            "kcmc": "实验课",
                            "xqj": "5",
                            "jcs": "5-6,7-8",
                            "cdmc": "实验楼",
                            "xm": "王老师",
                            "zcmc": "2-10周",
                            "xf": "2",
                            "xqh_id": "1",
                        },
                    ],
                },
                ensure_ascii=False,
            ).encode()
            self._send(200, body, "application/json; charset=utf-8")
            return

        if path == "/jwglxt/kbcx/xskbcx_cxRjc.html":
            if not self._authed():
                self._redirect_login()
                return
            if form.get("xqh_id", [""])[0] != "1":
                self._send(400, b"invalid campus", "text/plain")
                return
            body = json.dumps(
                [
                    {"jcmc": "1", "qssj": "08:20", "jssj": "09:05"},
                    {"jcmc": "2", "qssj": "09:15", "jssj": "10:00"},
                    {"jcmc": "3", "qssj": "10:20", "jssj": "11:05"},
                    {"jcmc": "4", "qssj": "11:15", "jssj": "12:00"},
                    {"jcmc": "5", "qssj": "14:00", "jssj": "14:45"},
                    {"jcmc": "6", "qssj": "14:55", "jssj": "15:40"},
                    {"jcmc": "7", "qssj": "16:00", "jssj": "16:45"},
                    {"jcmc": "8", "qssj": "16:55", "jssj": "17:40"},
                ]
            ).encode()
            self._send(200, body, "application/json; charset=utf-8")
            return

        self._send(404, b"not found", "text/plain")


class JwglxtProtocolTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.temp_dir = tempfile.TemporaryDirectory()
        cls.key_path = Path(cls.temp_dir.name) / "key.pem"
        subprocess.run(
            [
                "openssl",
                "genpkey",
                "-algorithm",
                "RSA",
                "-pkeyopt",
                "rsa_keygen_bits:1024",
                "-out",
                str(cls.key_path),
            ],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        modulus_line = subprocess.check_output(
            ["openssl", "rsa", "-in", str(cls.key_path), "-noout", "-modulus"],
            text=True,
        ).strip()
        modulus_hex = modulus_line.split("=", 1)[1]
        cls.modulus_b64 = base64.b64encode(
            bytes.fromhex(modulus_hex)
        ).decode("ascii")

    @classmethod
    def tearDownClass(cls) -> None:
        cls.temp_dir.cleanup()

    def start_server(
        self,
        *,
        cas_expiry: bool = False,
        direct_ticket: bool = True,
    ) -> tuple[ThreadingHTTPServer, str, MockState]:
        state = MockState(
            self.key_path,
            self.modulus_b64,
            "secret",
            cas_expiry=cas_expiry,
            direct_ticket=direct_ticket,
        )
        server = ThreadingHTTPServer(("127.0.0.1", 0), MockHandler)
        server.state = state
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        base_url = f"http://127.0.0.1:{server.server_port}/jwglxt"
        return server, base_url, state

    def test_rsa_password_encryption(self) -> None:
        encrypted = _rsa_encrypt_password("secret", self.modulus_b64, "AQAB")
        state = MockState(self.key_path, self.modulus_b64, "secret")
        self.assertEqual(state.decrypt_password(encrypted), "secret")

    def test_cas_password_encryption(self) -> None:
        encrypted = _cas_rsa_encrypt("secret")
        state = MockState(self.key_path, self.modulus_b64, "secret")
        self.assertEqual(state.decrypt_cas_password(encrypted), "secret")

    def test_cas_login_and_schedule(self) -> None:
        _, base_url, state = self.start_server(direct_ticket=False)
        root_url = base_url.removesuffix("/jwglxt")
        client = JwglxtClient(
            base_url,
            cas_base_url=f"{root_url}/lyuapServer",
            sso_url=f"{root_url}/sso/lyiotlogin",
        )
        client.begin_cas_session()
        captcha = client.fetch_cas_captcha()
        self.assertEqual(captcha["captchaId"], state.captcha_id)
        self.assertTrue(captcha["image"].startswith("data:image/png;base64,"))
        client.login_with_cas(
            "2026001",
            "secret",
            captcha["captchaId"],
            state.captcha_code,
        )
        schedule = client.fetch_schedule()
        self.assertEqual(schedule["term"]["label"], "2026-2027-1")
        self.assertEqual(len(schedule["courses"]), 4)

    def test_cas_captcha_rejection(self) -> None:
        _, base_url, state = self.start_server()
        root_url = base_url.removesuffix("/jwglxt")
        client = JwglxtClient(
            base_url,
            cas_base_url=f"{root_url}/lyuapServer",
            sso_url=f"{root_url}/sso/lyiotlogin",
        )
        client.begin_cas_session()
        captcha = client.fetch_cas_captcha()
        with self.assertRaises(CaptchaRejected):
            client.login_with_cas(
                "2026001",
                "secret",
                captcha["captchaId"],
                "0000",
            )
        self.assertEqual(state.captcha_id, captcha["captchaId"])

    def test_login_and_normalize_personal_schedule(self) -> None:
        _, base_url, _ = self.start_server()
        client = JwglxtClient(base_url)
        client.login("2026001", "secret")
        schedule = client.fetch_schedule()

        self.assertEqual(schedule["term"]["label"], "2026-2027-1")
        self.assertEqual(schedule["studentName"], "测试同学")
        self.assertEqual(len(schedule["courses"]), 4)
        self.assertEqual(schedule["periods"][0]["start"], "08:20")

        english = next(
            course for course in schedule["courses"] if course["name"] == "大学英语"
        )
        self.assertEqual(english["weeks"], [1, 3, 5, 7, 9, 11, 13, 15])

        lab_blocks = [
            (course["startPeriod"], course["endPeriod"])
            for course in schedule["courses"]
            if course["name"] == "实验课"
        ]
        self.assertEqual(lab_blocks, [(5, 6), (7, 8)])

    def test_rejected_login_is_not_retried(self) -> None:
        server, base_url, _ = self.start_server()
        client = JwglxtClient(base_url)
        with self.assertRaises(LoginRejected):
            client.login("2026001", "wrong-password")
        self.assertEqual(server.RequestHandlerClass, MockHandler)

    def test_exported_cookies_restore_session(self) -> None:
        _, base_url, _ = self.start_server()
        client = JwglxtClient(base_url)
        client.login("2026001", "secret")
        cookies = client.export_cookies()

        restored = JwglxtClient(base_url)
        restored.import_cookies(cookies)
        schedule = restored.fetch_schedule()
        self.assertEqual(schedule["studentName"], "测试同学")

    def test_missing_session_is_expired(self) -> None:
        _, base_url, _ = self.start_server()
        client = JwglxtClient(base_url)
        with self.assertRaises(SessionExpired):
            client.fetch_schedule()

    def test_cas_redirect_is_expired(self) -> None:
        _, base_url, _ = self.start_server(cas_expiry=True)
        client = JwglxtClient(base_url)
        with self.assertRaises(SessionExpired):
            client.fetch_schedule()

    def test_sync_without_session_requires_login(self) -> None:
        with tempfile.TemporaryDirectory() as data_dir:
            app = create_app(
                {
                    "TESTING": True,
                    "DATA_DIR": data_dir,
                    "JWXT_BASE_URL": "http://127.0.0.1:1/jwglxt",
                    "JWXT_VERIFY_TLS": "1",
                }
            )
            response = app.test_client().post("/api/sync", json={})
            self.assertEqual(response.status_code, 401)
            self.assertEqual(response.get_json()["code"], "login_required")

    def test_flask_login_and_sync(self) -> None:
        _, base_url, state = self.start_server()
        root_url = base_url.removesuffix("/jwglxt")
        with tempfile.TemporaryDirectory() as data_dir:
            app = create_app(
                {
                    "TESTING": True,
                    "DATA_DIR": data_dir,
                    "JWXT_BASE_URL": base_url,
                    "CAS_BASE_URL": f"{root_url}/lyuapServer",
                    "JWXT_SSO_URL": f"{root_url}/sso/lyiotlogin",
                    "JWXT_VERIFY_TLS": "1",
                }
            )
            web = app.test_client()
            captcha_response = web.get("/api/captcha")
            self.assertEqual(captcha_response.status_code, 200)
            captcha = captcha_response.get_json()
            response = web.post(
                "/api/login",
                json={
                    "username": "2026001",
                    "password": "secret",
                    "captchaId": captcha["captchaId"],
                    "captchaCode": state.captcha_code,
                    "rememberSession": True,
                },
            )
            self.assertEqual(response.status_code, 200)
            payload = response.get_json()
            self.assertEqual(len(payload["schedule"]["courses"]), 4)
            self.assertTrue((Path(data_dir) / "schedule.json").exists())
            self.assertTrue((Path(data_dir) / "session.cookies.json").exists())

            sync_response = web.post("/api/sync", json={})
            self.assertEqual(sync_response.status_code, 200)

    def test_week_text_with_odd_and_even_filter(self) -> None:
        self.assertEqual(
            _parse_weeks({"zcmc": "1-10周(单)"}),
            [1, 3, 5, 7, 9],
        )
        self.assertEqual(
            _parse_weeks({"zcmc": "1-10周(双)"}),
            [2, 4, 6, 8, 10],
        )

    def test_distinct_teaching_classes_are_not_merged(self) -> None:
        from jwglxt import _normalize_courses

        rows = [
            {
                "kcmc": "同一门课",
                "xqj": "1",
                "jcs": "1-2",
                "cdmc": "A101",
                "xm": "张老师",
                "zcmc": "1-16周",
                "jxb_id": "class-a",
            },
            {
                "kcmc": "同一门课",
                "xqj": "1",
                "jcs": "1-2",
                "cdmc": "A101",
                "xm": "张老师",
                "zcmc": "1-16周",
                "jxb_id": "class-b",
            },
        ]
        courses = _normalize_courses(rows)
        self.assertEqual(len(courses), 2)
        self.assertEqual(
            {course["teachingClassId"] for course in courses},
            {"class-a", "class-b"},
        )

    def test_term_parse(self) -> None:
        self.assertEqual(Term.parse("2026-2027-1"), Term(2026, 1))
        self.assertEqual(Term.parse("2026-2"), Term(2026, 2))


if __name__ == "__main__":
    unittest.main()
