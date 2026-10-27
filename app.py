from __future__ import annotations

import json
import os
import threading
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Any

from flask import Flask, jsonify, render_template, request, send_from_directory

from jwglxt import (
    CaptchaRequired,
    CaptchaRejected,
    JwglxtClient,
    JwglxtError,
    LoginRejected,
    SessionExpired,
    TransportError,
    create_client_from_env,
)


APP_ROOT = Path(__file__).resolve().parent


class AppState:
    def __init__(
        self,
        data_dir: Path,
        base_url: str,
        cas_base_url: str,
        sso_url: str,
        verify_tls: bool,
    ) -> None:
        self.data_dir = data_dir
        self.base_url = base_url
        self.cas_base_url = cas_base_url
        self.sso_url = sso_url
        self.verify_tls = verify_tls
        self.schedule_path = data_dir / "schedule.json"
        self.cookies_path = data_dir / "session.cookies.json"
        self.lock = threading.RLock()
        self.client: JwglxtClient | None = None
        self.pending_client: JwglxtClient | None = None
        self.data_dir.mkdir(parents=True, exist_ok=True)

    def new_client(self) -> JwglxtClient:
        return JwglxtClient(
            self.base_url,
            cas_base_url=self.cas_base_url,
            sso_url=self.sso_url,
            verify_tls=self.verify_tls,
        )

    def load_schedule(self) -> dict[str, Any] | None:
        return self._read_json(self.schedule_path)

    def load_cookies(self) -> list[dict[str, Any]]:
        payload = self._read_json(self.cookies_path)
        if not isinstance(payload, dict):
            return []
        cookies = payload.get("cookies")
        return cookies if isinstance(cookies, list) else []

    def save_cookies(self, client: JwglxtClient) -> None:
        self._write_json(
            self.cookies_path,
            {
                "savedAt": _utc_now(),
                "cookies": client.export_cookies(),
            },
            private=True,
        )

    def forget_saved_session(self) -> None:
        try:
            self.cookies_path.unlink()
        except FileNotFoundError:
            pass

    def save_schedule(self, schedule: dict[str, Any]) -> None:
        self._write_json(self.schedule_path, schedule)

    def get_client(self) -> JwglxtClient | None:
        if self.client:
            return self.client

        cookies = self.load_cookies()
        if not cookies:
            return None

        client = self.new_client()
        client.import_cookies(cookies)
        self.client = client
        return client

    @staticmethod
    def _read_json(path: Path) -> Any:
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except (FileNotFoundError, json.JSONDecodeError):
            return None

    @staticmethod
    def _write_json(path: Path, payload: Any, *, private: bool = False) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        temp_path = path.with_suffix(f"{path.suffix}.tmp")
        temp_path.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        os.replace(temp_path, path)
        try:
            path.chmod(0o600 if private else 0o644)
        except OSError:
            pass


def _utc_now() -> str:
    return (
        datetime.now(timezone.utc)
        .replace(microsecond=0)
        .isoformat()
        .replace("+00:00", "Z")
    )


def _with_current_week(schedule: dict[str, Any]) -> dict[str, Any]:
    result = dict(schedule)
    term = result.get("term")
    if isinstance(term, dict):
        try:
            start = date.fromisoformat(str(term["startDate"]))
            week = (date.today() - start).days // 7 + 1
            result["currentWeek"] = min(20, max(1, week))
        except (KeyError, ValueError):
            pass
    return result


def _error_response(message: str, code: str, status: int = 400):
    return jsonify({"error": message, "code": code}), status


def create_app(config: dict[str, Any] | None = None) -> Flask:
    app = Flask(
        __name__,
        template_folder=str(APP_ROOT / "templates"),
        static_folder=str(APP_ROOT / "static"),
    )
    app.config.from_mapping(
        DATA_DIR=os.environ.get(
            "COURSE_DATA_DIR",
            str(APP_ROOT / "data"),
        ),
        JWXT_BASE_URL=os.environ.get("JWXT_BASE_URL"),
        CAS_BASE_URL=os.environ.get("CAS_BASE_URL"),
        JWXT_SSO_URL=os.environ.get("JWXT_SSO_URL"),
        JWXT_VERIFY_TLS=os.environ.get("JWXT_VERIFY_TLS", "1"),
    )
    if config:
        app.config.update(config)

    if app.config.get("JWXT_BASE_URL"):
        os.environ["JWXT_BASE_URL"] = str(app.config["JWXT_BASE_URL"])
    if app.config.get("CAS_BASE_URL"):
        os.environ["CAS_BASE_URL"] = str(app.config["CAS_BASE_URL"])
    if app.config.get("JWXT_SSO_URL"):
        os.environ["JWXT_SSO_URL"] = str(app.config["JWXT_SSO_URL"])
    os.environ["JWXT_VERIFY_TLS"] = str(app.config["JWXT_VERIFY_TLS"])
    bootstrap = create_client_from_env()
    state = AppState(
        Path(app.config["DATA_DIR"]),
        bootstrap.base_url,
        bootstrap.cas_base_url,
        bootstrap.sso_url,
        bootstrap.verify_tls,
    )
    app.extensions["course_state"] = state

    @app.get("/")
    def index():
        return render_template("index.html")

    @app.get("/favicon.ico")
    def favicon():
        return send_from_directory(
            APP_ROOT / "static",
            "favicon.svg",
            mimetype="image/svg+xml",
        )

    @app.get("/api/status")
    def status():
        with state.lock:
            schedule = state.load_schedule()
            cookies = state.load_cookies()
        term = schedule.get("term") if isinstance(schedule, dict) else None
        return jsonify(
            {
                "hasSchedule": bool(schedule),
                "hasSavedSession": bool(cookies),
                "term": term,
                "fetchedAt": schedule.get("fetchedAt")
                if isinstance(schedule, dict)
                else None,
                "currentWeek": _with_current_week(schedule).get("currentWeek")
                if isinstance(schedule, dict)
                else None,
            }
        )

    @app.get("/api/schedule")
    def get_schedule():
        with state.lock:
            schedule = state.load_schedule()
        if not schedule:
            return _error_response("本地还没有课程数据", "no_schedule", 404)
        return jsonify(_with_current_week(schedule))

    @app.get("/api/captcha")
    def captcha():
        with state.lock:
            client = state.pending_client
            if client is None:
                client = state.new_client()
                try:
                    client.begin_cas_session()
                except TransportError as exc:
                    return _error_response(str(exc), "transport_error", 502)
                except JwglxtError as exc:
                    return _error_response(str(exc), "captcha_error", 502)
                state.pending_client = client
            try:
                payload = client.fetch_cas_captcha()
            except TransportError as exc:
                return _error_response(str(exc), "transport_error", 502)
            except JwglxtError as exc:
                return _error_response(str(exc), "captcha_error", 502)
        return jsonify(payload)

    @app.post("/api/login")
    def login():
        payload = request.get_json(silent=True) or {}
        username = str(payload.get("username", "")).strip()
        password = str(payload.get("password", ""))
        term = str(payload.get("term", "")).strip() or None
        remember = bool(payload.get("rememberSession", True))
        captcha_id = str(payload.get("captchaId", "")).strip()
        captcha_code = str(payload.get("captchaCode", "")).strip()

        if not username or not password:
            return _error_response("请输入学号和密码", "missing_credentials")

        with state.lock:
            client = state.pending_client
            if client is None:
                client = state.new_client()
                try:
                    client.begin_cas_session()
                except TransportError as exc:
                    return _error_response(str(exc), "transport_error", 502)
                except JwglxtError as exc:
                    return _error_response(str(exc), "login_error", 502)

            try:
                client.login_with_cas(
                    username,
                    password,
                    captcha_id,
                    captcha_code,
                )
            except CaptchaRejected as exc:
                state.pending_client = client
                try:
                    fresh_captcha = client.fetch_cas_captcha()
                except JwglxtError:
                    fresh_captcha = None
                response = {
                    "error": str(exc),
                    "code": "captcha_rejected",
                }
                if fresh_captcha:
                    response["captcha"] = fresh_captcha
                return jsonify(response), 409
            except CaptchaRequired as exc:
                state.pending_client = client
                try:
                    fresh_captcha = client.fetch_cas_captcha()
                except JwglxtError:
                    fresh_captcha = None
                response = {
                    "error": str(exc),
                    "code": "captcha_required",
                }
                if fresh_captcha:
                    response["captcha"] = fresh_captcha
                return jsonify(response), 409
            except LoginRejected as exc:
                state.pending_client = client
                try:
                    fresh_captcha = client.fetch_cas_captcha()
                except JwglxtError:
                    fresh_captcha = None
                response = {
                    "error": str(exc),
                    "code": "login_rejected",
                }
                if fresh_captcha:
                    response["captcha"] = fresh_captcha
                return jsonify(response), 401
            except TransportError as exc:
                return _error_response(str(exc), "transport_error", 502)
            except JwglxtError as exc:
                return _error_response(str(exc), "login_error", 502)

            state.client = client
            state.pending_client = None
            try:
                schedule = client.fetch_schedule(term)
            except SessionExpired as exc:
                state.client = None
                return _error_response(str(exc), "session_expired", 401)
            except JwglxtError as exc:
                if remember:
                    state.save_cookies(client)
                return _error_response(
                    f"登录成功，但读取课表失败：{exc}",
                    "schedule_error",
                    502,
                )

            if remember:
                state.save_cookies(client)
            else:
                state.forget_saved_session()
            state.save_schedule(schedule)

        return jsonify(
            {
                "ok": True,
                "schedule": _with_current_week(schedule),
            }
        )

    @app.post("/api/sync")
    def sync():
        payload = request.get_json(silent=True) or {}
        term = str(payload.get("term", "")).strip() or None

        with state.lock:
            client = state.get_client()
            if not client:
                return _error_response(
                    "没有可用的教务会话，请先登录",
                    "login_required",
                    401,
                )
            try:
                schedule = client.fetch_schedule(term)
            except SessionExpired as exc:
                state.client = None
                state.forget_saved_session()
                return _error_response(str(exc), "session_expired", 401)
            except TransportError as exc:
                return _error_response(str(exc), "transport_error", 502)
            except JwglxtError as exc:
                return _error_response(str(exc), "sync_error", 502)

            state.save_schedule(schedule)
            if state.load_cookies():
                state.save_cookies(client)

        return jsonify({"ok": True, "schedule": _with_current_week(schedule)})

    @app.post("/api/logout")
    def logout():
        with state.lock:
            state.client = None
            state.pending_client = None
            state.forget_saved_session()
        return jsonify({"ok": True})

    return app


app = create_app()


if __name__ == "__main__":
    app.run(
        host="127.0.0.1",
        port=int(os.environ.get("COURSE_PORT", "8765")),
        debug=False,
    )
