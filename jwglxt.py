from __future__ import annotations

import base64
import hashlib
import os
import re
import secrets
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from typing import Any, Iterable
from urllib.parse import parse_qsl, quote, urlencode, urlparse, urlunparse

import requests
from bs4 import BeautifulSoup


DEFAULT_BASE_URL = "https://jwxt.gzus.edu.cn/jwglxt"
DEFAULT_CAS_BASE_URL = "https://cas.gzus.edu.cn/lyuapServer"
DEFAULT_SSO_URL = "https://jwxt.gzus.edu.cn/sso/lyiotlogin"
CAS_MODULUS_HEX = (
    "00b5eeb166e069920e80bebd1fea4829d3d1f3216f2aabe79b6c47a3c18dcee5"
    "fd22c2e7ac519cab59198ece036dcf289ea8201e2a0b9ded307f8fb704136eae"
    "b670286f5ad44e691005ba9ea5af04ada5367cd724b5a26fdb5120cc95b64316"
    "04bd219c6b7d83a6f8f24b43918ea988a76f93c333aa5a20991493d4eb1117e7b1"
)
CAS_PUBLIC_EXPONENT = 65537
CAS_MODULUS = int(CAS_MODULUS_HEX, 16)
CAS_KEY_SIZE = (CAS_MODULUS.bit_length() + 7) // 8
TERM_CODES = {1: "3", 2: "12", 3: "16"}
CODE_TO_TERM = {value: key for key, value in TERM_CODES.items()}
PERSONAL_TIMETABLE_PAGE = (
    "/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default"
)
PERSONAL_TIMETABLE_QUERY = "/kbcx/xskbcx_cxXsgrkb.html"
PERIOD_QUERY = "/kbcx/xskbcx_cxRjc.html"


class JwglxtError(RuntimeError):
    pass


class LoginRejected(JwglxtError):
    pass


class SessionExpired(JwglxtError):
    pass


class CaptchaRequired(JwglxtError):
    pass


class CaptchaRejected(JwglxtError):
    pass


class TransportError(JwglxtError):
    pass


@dataclass(frozen=True)
class Term:
    year: int
    number: int

    @property
    def label(self) -> str:
        return f"{self.year}-{self.year + 1}-{self.number}"

    @property
    def xnm(self) -> str:
        return str(self.year)

    @property
    def xqm(self) -> str:
        return TERM_CODES[self.number]

    @property
    def start_date(self) -> date:
        fixed_dates = {
            "2025-2026-2": date(2026, 3, 2),
            "2026-2027-1": date(2026, 8, 31),
            "2026-2027-2": date(2027, 3, 1),
        }
        known = fixed_dates.get(self.label)
        if known:
            return known

        if self.number == 1:
            anchor = date(self.year, 9, 1)
        elif self.number == 2:
            anchor = date(self.year + 1, 3, 1)
        else:
            anchor = date(self.year + 1, 7, 1)
        return anchor - timedelta(days=anchor.weekday())

    @classmethod
    def parse(cls, value: str | "Term") -> "Term":
        if isinstance(value, Term):
            return value

        match = re.fullmatch(r"(\d{4})(?:-(\d{4}))?-([123])", value.strip())
        if not match:
            raise ValueError("学期格式应为 2026-2027-1 或 2026-1")

        year = int(match.group(1))
        end_year = match.group(2)
        number = int(match.group(3))
        if end_year and int(end_year) != year + 1:
            raise ValueError("学年结束年份必须比开始年份大 1")
        return cls(year=year, number=number)


def guess_current_term(today: date | None = None) -> Term:
    today = today or date.today()
    if today.month >= 8:
        return Term(today.year, 1)
    if today.month <= 1:
        return Term(today.year - 1, 1)
    return Term(today.year - 1, 2)


def _rsa_encrypt_password(password: str, modulus_b64: str, exponent_b64: str) -> str:
    try:
        modulus = int.from_bytes(base64.b64decode(modulus_b64), "big")
        exponent = int.from_bytes(base64.b64decode(exponent_b64), "big")
    except Exception as exc:
        raise JwglxtError("教务系统返回了无效的登录公钥") from exc

    message = password.encode("utf-8")
    key_size = (modulus.bit_length() + 7) // 8
    padding_size = key_size - len(message) - 3
    if padding_size < 8:
        raise JwglxtError("登录公钥长度不足")

    padding = bytearray()
    while len(padding) < padding_size:
        byte = secrets.randbelow(255) + 1
        if byte != 0:
            padding.append(byte)

    encoded = b"\x00\x02" + bytes(padding) + b"\x00" + message
    encrypted = pow(int.from_bytes(encoded, "big"), exponent, modulus)
    return base64.b64encode(encrypted.to_bytes(key_size, "big")).decode("ascii")


def _cas_rsa_encrypt(value: str) -> str:
    try:
        raw = value.encode("latin-1")
    except UnicodeEncodeError as exc:
        raise LoginRejected("CAS 密码包含当前流程不支持的字符") from exc
    chunks: list[str] = []
    for offset in range(0, len(raw), CAS_KEY_SIZE):
        chunk = raw[offset : offset + CAS_KEY_SIZE].ljust(
            CAS_KEY_SIZE,
            b"\x00",
        )
        encrypted = pow(
            int.from_bytes(chunk, "little"),
            CAS_PUBLIC_EXPONENT,
            CAS_MODULUS,
        )
        chunks.append(format(encrypted, "x"))
    return " ".join(chunks) or "0"


def _find_input_value(soup: BeautifulSoup, element_id: str) -> str:
    node = soup.find("input", {"id": element_id})
    if not node:
        node = soup.find("input", {"name": element_id})
    if not node:
        return ""
    return str(node.get("value", "")).strip()


def _extract_tips(html: str) -> str:
    soup = BeautifulSoup(html, "html.parser")
    tips = soup.find(id="tips")
    if tips:
        return " ".join(tips.get_text(" ", strip=True).split())
    page_text = " ".join(soup.get_text(" ", strip=True).split())
    for phrase in ("用户名或密码不正确", "密码错误", "用户不存在"):
        if phrase in page_text:
            return phrase
    return "教务系统拒绝了登录，请检查学号和密码"


def _parse_selected_term(soup: BeautifulSoup) -> Term | None:
    values: dict[str, str] = {}
    for name in ("xnm", "xqm"):
        select = soup.find("select", {"name": name})
        if not select:
            continue
        option = select.find("option", selected=True)
        if option and option.get("value"):
            values[name] = str(option["value"]).strip()

    try:
        year = int(values["xnm"])
    except (KeyError, ValueError):
        return None

    number = CODE_TO_TERM.get(values.get("xqm", ""))
    if not number:
        return None
    return Term(year, number)


def _parse_terms(soup: BeautifulSoup) -> list[Term]:
    year_select = soup.find("select", {"name": "xnm"})
    term_select = soup.find("select", {"name": "xqm"})
    if not year_select or not term_select:
        return []

    years = [
        int(str(option.get("value", "")))
        for option in year_select.find_all("option")
        if str(option.get("value", "")).isdigit()
    ]
    numbers = [
        CODE_TO_TERM[str(option.get("value", ""))]
        for option in term_select.find_all("option")
        if str(option.get("value", "")) in CODE_TO_TERM
    ]

    terms = [Term(year, number) for year in years for number in numbers]
    return sorted(set(terms), key=lambda term: (term.year, term.number))


def _parse_weeks(course: dict[str, Any]) -> list[int]:
    text = str(
        course.get("zcmc")
        or course.get("zcd")
        or course.get("zcmc_display")
        or ""
    ).strip()
    if not text:
        return list(range(1, 21))

    compact = re.sub(r"\s+", "", text)
    if re.fullmatch(r"[01]+", compact) and len(compact) >= 8:
        return [index + 1 for index, flag in enumerate(compact) if flag == "1"]

    weeks: set[int] = set()
    for start_text, end_text in re.findall(r"(\d{1,2})(?:-(\d{1,2}))?", compact):
        start = int(start_text)
        end = int(end_text) if end_text else start
        if 1 <= start <= 30 and 1 <= end <= 30:
            if start > end:
                start, end = end, start
            weeks.update(range(start, end + 1))

    if not weeks:
        return list(range(1, 21))

    odd_only = "单" in compact or "odd" in compact.lower()
    even_only = "双" in compact or "even" in compact.lower()
    if odd_only and not even_only:
        weeks = {week for week in weeks if week % 2 == 1}
    elif even_only and not odd_only:
        weeks = {week for week in weeks if week % 2 == 0}
    return sorted(weeks)


def _parse_period_blocks(course: dict[str, Any]) -> list[tuple[int, int]]:
    text = str(
        course.get("jcs")
        or course.get("jc")
        or course.get("jcor")
        or course.get("jcstr")
        or ""
    ).strip()
    matches = re.findall(r"(\d{1,2})(?:-(\d{1,2}))?", text)
    blocks: list[tuple[int, int]] = []

    for start_text, end_text in matches:
        start = int(start_text)
        end = int(end_text) if end_text else start
        if start > end:
            start, end = end, start
        if 1 <= start <= 20 and 1 <= end <= 20:
            blocks.append((start, end))

    return blocks or [(1, 1)]


def _parse_weekday(value: Any) -> int | None:
    text = str(value or "").strip()
    names = {
        "周一": 1,
        "周二": 2,
        "周三": 3,
        "周四": 4,
        "周五": 5,
        "周六": 6,
        "周日": 7,
        "周天": 7,
        "星期一": 1,
        "星期二": 2,
        "星期三": 3,
        "星期四": 4,
        "星期五": 5,
        "星期六": 6,
        "星期日": 7,
        "星期天": 7,
    }
    if text in names:
        return names[text]
    match = re.search(r"[1-7]", text)
    return int(match.group()) if match else None


def _first(course: dict[str, Any], *keys: str) -> str:
    for key in keys:
        value = course.get(key)
        if value is not None and str(value).strip():
            return str(value).strip()
    return ""


def _default_periods() -> list[dict[str, Any]]:
    return [
        {"index": index, "label": str(index), "start": "", "end": ""}
        for index in range(1, 13)
    ]


def _normalize_periods(raw: Any) -> list[dict[str, Any]]:
    periods: list[dict[str, Any]] = []
    if isinstance(raw, list):
        for index, item in enumerate(raw, start=1):
            if not isinstance(item, dict):
                continue
            label = _first(item, "jcmc", "jc", "jcs") or str(index)
            match = re.search(r"\d+", label)
            period_index = int(match.group()) if match else index
            periods.append(
                {
                    "index": period_index,
                    "label": label,
                    "start": _first(item, "qssj", "kssj"),
                    "end": _first(item, "jssj", "sksj"),
                }
            )

    by_index = {period["index"]: period for period in periods}
    if not periods:
        return _default_periods()

    max_index = max(12, max(by_index))
    for index in range(1, max_index + 1):
        by_index.setdefault(
            index,
            {"index": index, "label": str(index), "start": "", "end": ""},
        )
    return [by_index[index] for index in sorted(by_index)]


def _normalize_courses(raw_courses: Iterable[dict[str, Any]]) -> list[dict[str, Any]]:
    courses: list[dict[str, Any]] = []
    seen: set[str] = set()

    for raw in raw_courses:
        if not isinstance(raw, dict):
            continue

        name = _first(raw, "kcmc", "kcname", "courseName")
        weekday = _parse_weekday(raw.get("xqj") or raw.get("weekday"))
        if not name or not weekday:
            continue

        teacher = _first(raw, "xm", "jsxm", "teacherName")
        room = _first(raw, "cdmc", "roomName")
        campus = _first(raw, "xqmc", "campusName")
        if campus and room and campus not in room:
            room = f"{campus} {room}"

        weeks = _parse_weeks(raw)
        week_text = _first(raw, "zcmc", "weekText")
        if not week_text and weeks:
            week_text = f"{weeks[0]}-{weeks[-1]}周"

        credit = _first(raw, "xf", "credit")
        course_code = _first(raw, "kch", "kcbm", "courseCode")
        teaching_class_id = _first(raw, "jxb_id")
        teaching_class_name = _first(raw, "jxbmc")

        for start, end in _parse_period_blocks(raw):
            identity = "|".join(
                [
                    teaching_class_id,
                    name,
                    teacher,
                    room,
                    str(weekday),
                    str(start),
                    str(end),
                    ",".join(map(str, weeks)),
                ]
            )
            course_id = hashlib.sha1(identity.encode("utf-8")).hexdigest()[:16]
            if course_id in seen:
                continue
            seen.add(course_id)
            courses.append(
                {
                    "id": course_id,
                    "name": name,
                    "teacher": teacher,
                    "room": room,
                    "weekday": weekday,
                    "startPeriod": start,
                    "endPeriod": end,
                    "periods": list(range(start, end + 1)),
                    "weeks": weeks,
                    "weekText": week_text,
                    "credit": credit,
                    "courseCode": course_code,
                    "teachingClassId": teaching_class_id,
                    "teachingClassName": teaching_class_name,
                }
            )

    return sorted(
        courses,
        key=lambda course: (
            course["weekday"],
            course["startPeriod"],
            course["name"],
        ),
    )


def _extract_course_rows(payload: Any) -> list[dict[str, Any]]:
    if not isinstance(payload, dict):
        return []

    rows: list[dict[str, Any]] = []
    for key in ("kbList", "sjkList", "items"):
        value = payload.get(key)
        if isinstance(value, list):
            rows.extend(item for item in value if isinstance(item, dict))
    return rows


class JwglxtClient:
    def __init__(
        self,
        base_url: str = DEFAULT_BASE_URL,
        *,
        cas_base_url: str = DEFAULT_CAS_BASE_URL,
        sso_url: str = DEFAULT_SSO_URL,
        timeout: float = 30.0,
        verify_tls: bool = True,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.cas_base_url = cas_base_url.rstrip("/")
        self.sso_url = sso_url
        self.timeout = timeout
        self.verify_tls = verify_tls
        self.session = requests.Session()
        self.session.headers.update(
            {
                "User-Agent": (
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    "AppleWebKit/537.36 (KHTML, like Gecko) "
                    "Chrome/130.0 Safari/537.36"
                ),
                "Accept-Language": "zh-CN,zh;q=0.9",
            }
        )

    def _url(self, path: str) -> str:
        return f"{self.base_url}/{path.lstrip('/')}"

    def _request_url(
        self,
        method: str,
        url: str,
        *,
        data: dict[str, Any] | None = None,
        params: dict[str, Any] | None = None,
        xhr: bool = False,
        allow_redirects: bool = False,
    ) -> requests.Response:
        headers = {"X-Requested-With": "XMLHttpRequest"} if xhr else {}
        try:
            return self.session.request(
                method,
                url,
                data=data,
                params=params,
                headers=headers,
                timeout=self.timeout,
                verify=self.verify_tls,
                allow_redirects=allow_redirects,
            )
        except requests.RequestException as exc:
            raise TransportError(f"无法连接学校认证系统：{exc}") from exc

    def _request(
        self,
        method: str,
        path: str,
        *,
        data: dict[str, Any] | None = None,
        xhr: bool = False,
        allow_redirects: bool = False,
    ) -> requests.Response:
        return self._request_url(
            method,
            self._url(path),
            data=data,
            xhr=xhr,
            allow_redirects=allow_redirects,
        )

    @staticmethod
    def _is_login_response(response: requests.Response) -> bool:
        location = response.headers.get("Location", "")
        current_url = response.url or ""
        current_path = urlparse(current_url).path.lower()
        location_path = urlparse(location).path.lower()
        if (
            "login_slogin" in location.lower()
            or "login_slogin" in current_path
            or "lyuapserver/login" in location_path
            or "lyuapserver/login" in current_path
        ):
            return True
        content_type = response.headers.get("Content-Type", "")
        if "html" not in content_type.lower():
            return False
        return any(
            marker in response.text
            for marker in (
                "login_getPublicKey",
                'id="yhm"',
                "统一身份认证平台",
            )
        )

    def login(self, username: str, password: str) -> None:
        username = username.strip()
        if not username or not password:
            raise LoginRejected("请输入学号和密码")

        page_response = self._request("GET", "/xtgl/login_slogin.html")
        if page_response.status_code != 200:
            raise JwglxtError(
                f"登录页返回异常状态：HTTP {page_response.status_code}"
            )

        soup = BeautifulSoup(page_response.text, "html.parser")
        csrf = _find_input_value(soup, "csrftoken")
        encrypted_password = _find_input_value(soup, "mmsfjm")
        captcha_required = _find_input_value(soup, "dlsfbxyzm")
        if captcha_required == "1":
            raise CaptchaRequired("教务系统当前要求验证码，请先在浏览器完成一次登录")

        if encrypted_password == "0":
            submitted_password = password
        else:
            key_response = self._request(
                "GET",
                f"/xtgl/login_getPublicKey.html?time={int(datetime.now().timestamp() * 1000)}",
            )
            try:
                key = key_response.json()
            except ValueError as exc:
                raise JwglxtError("无法读取教务系统登录公钥") from exc
            submitted_password = _rsa_encrypt_password(
                password,
                str(key.get("modulus", "")),
                str(key.get("exponent", "")),
            )

        form = {
            "csrftoken": csrf,
            "language": "zh_CN",
            "ydType": "",
            "yhm": username,
            "mm": submitted_password,
        }
        response = self._request(
            "POST",
            f"/xtgl/login_slogin.html?time={int(datetime.now().timestamp() * 1000)}",
            data=form,
        )

        if 300 <= response.status_code < 400:
            return
        if response.status_code == 200:
            raise LoginRejected(_extract_tips(response.text))
        raise LoginRejected(
            f"登录失败，教务系统返回 HTTP {response.status_code}"
        )

    def begin_cas_session(self) -> None:
        self._request_url(
            "GET",
            f"{self.cas_base_url}/login",
            params={"service": self.sso_url},
            allow_redirects=True,
        )

    def fetch_cas_captcha(self) -> dict[str, Any]:
        response = self._request_url(
            "GET",
            f"{self.cas_base_url}/kaptcha",
            params={"uid": ""},
            xhr=True,
        )
        if response.status_code != 200:
            raise JwglxtError(
                f"验证码接口返回异常状态：HTTP {response.status_code}"
            )
        try:
            payload = response.json()
        except ValueError as exc:
            raise JwglxtError("验证码接口没有返回有效 JSON") from exc

        uid = str(payload.get("uid", ""))
        image = str(payload.get("content", ""))
        if not uid or not image.startswith("data:image/"):
            raise JwglxtError("认证系统没有返回有效验证码")
        return {
            "captchaId": uid,
            "image": image,
            "timeout": payload.get("timeout", 300),
        }

    def _cas_ticket(self, tgt: str) -> str:
        response = self._request_url(
            "POST",
            f"{self.cas_base_url}/v1/tickets/{quote(tgt, safe='')}",
            data={"service": self.sso_url, "loginToken": "loginToken"},
            xhr=True,
        )
        if response.status_code not in {200, 201}:
            raise JwglxtError(
                f"CAS 服务票据接口返回 HTTP {response.status_code}"
            )
        try:
            payload = response.json()
        except ValueError:
            ticket = response.text.strip()
            if ticket:
                return ticket
            raise JwglxtError("CAS 服务票据接口返回为空")

        if isinstance(payload, str):
            return payload
        if isinstance(payload, dict):
            for key in ("ticket", "data"):
                value = payload.get(key)
                if isinstance(value, str) and value:
                    return value
        raise JwglxtError("CAS 服务票据接口没有返回票据")

    def _open_service_with_ticket(self, ticket: str) -> None:
        parsed = urlparse(self.sso_url)
        query = parse_qsl(parsed.query, keep_blank_values=True)
        query.append(("ticket", ticket))
        service_url = urlunparse(parsed._replace(query=urlencode(query)))
        response = self._request_url(
            "GET",
            service_url,
            allow_redirects=True,
        )
        if response.status_code >= 400:
            raise LoginRejected(
                f"CAS 票据校验失败：HTTP {response.status_code}"
            )

    def login_with_cas(
        self,
        username: str,
        password: str,
        captcha_id: str,
        captcha_code: str,
    ) -> None:
        username = username.strip()
        password = password.strip()
        captcha_code = captcha_code.strip()
        if not username or not password:
            raise LoginRejected("请输入账号和密码")
        if not captcha_id or not captcha_code:
            raise CaptchaRequired("请输入验证码")

        response = self._request_url(
            "POST",
            f"{self.cas_base_url}/v1/tickets",
            data={
                "username": username,
                "password": _cas_rsa_encrypt(password),
                "service": self.sso_url,
                "loginType": "",
                "id": captcha_id,
                "code": captcha_code,
            },
            xhr=True,
        )
        if response.status_code not in {200, 201}:
            raise LoginRejected(
                f"CAS 登录接口返回 HTTP {response.status_code}"
            )
        try:
            payload = response.json()
        except ValueError as exc:
            raise LoginRejected("CAS 登录接口没有返回有效 JSON") from exc

        meta = payload.get("meta") if isinstance(payload, dict) else None
        data = payload.get("data") if isinstance(payload, dict) else None
        if isinstance(data, dict) and data.get("code"):
            code = str(data["code"])
            message = str(meta.get("message", "")) if isinstance(meta, dict) else ""
            if code == "CODEFALSE":
                raise CaptchaRejected("验证码错误，请重新输入")
            if code == "FALSE":
                raise LoginRejected("账号或密码错误")
            if code == "NOUSER":
                raise LoginRejected("账号不存在")
            if code == "USERDISABLED":
                raise LoginRejected("账号已被停用，请联系学校管理员")
            if code == "USERLOCK":
                detail = str(data.get("data", "")).strip()
                raise LoginRejected(
                    f"账号已锁定{': ' + detail if detail else ''}"
                )
            if code == "PASSERROR":
                detail = str(data.get("data", "")).strip()
                raise LoginRejected(
                    f"密码错误{': ' + detail if detail else ''}"
                )
            if code == "TWOVERIFY":
                raise LoginRejected("该账号需要二次验证，目前无法自动登录")
            if code == "PEOPLEMOREACCOUNT":
                raise LoginRejected("该账号存在多个身份，需要先在学校页面选择")
            if code == "ISMODIFYPASS":
                raise LoginRejected("账号需要先修改密码")
            if code in {
                "ISPHONEOREMAILORANSWER",
                "ISBINDWX",
                "NETWORKCOMMITMENT",
            }:
                raise LoginRejected("账号需要额外安全验证，请先在学校页面完成")
            raise LoginRejected(message or f"CAS 登录失败：{code}")

        if isinstance(meta, dict) and meta.get("success") is False:
            raise LoginRejected(str(meta.get("message") or "CAS 登录失败"))

        ticket = ""
        tgt = ""
        if isinstance(payload, dict):
            ticket = str(payload.get("ticket") or "")
            tgt = str(payload.get("tgt") or "")
            if not ticket and isinstance(data, dict):
                ticket = str(data.get("ticket") or "")
                tgt = tgt or str(data.get("tgt") or "")
            if not tgt and isinstance(data, str):
                tgt = data

        if not ticket and tgt:
            ticket = self._cas_ticket(tgt)
        if not ticket:
            raise LoginRejected("CAS 登录成功，但没有返回服务票据")

        self._open_service_with_ticket(ticket)

    def export_cookies(self) -> list[dict[str, Any]]:
        return [
            {
                "name": cookie.name,
                "value": cookie.value,
                "domain": cookie.domain,
                "path": cookie.path,
                "secure": cookie.secure,
                "expires": cookie.expires,
            }
            for cookie in self.session.cookies
        ]

    def import_cookies(self, cookies: Iterable[dict[str, Any]]) -> None:
        for cookie in cookies:
            name = str(cookie.get("name", ""))
            value = str(cookie.get("value", ""))
            if not name:
                continue
            self.session.cookies.set(
                name,
                value,
                domain=str(cookie.get("domain") or ""),
                path=str(cookie.get("path") or "/"),
                secure=bool(cookie.get("secure")),
                expires=cookie.get("expires"),
            )

    def _load_timetable_page(self) -> tuple[BeautifulSoup, Term, list[Term]]:
        response = self._request(
            "GET",
            PERSONAL_TIMETABLE_PAGE,
            allow_redirects=True,
        )
        if self._is_login_response(response):
            raise SessionExpired("教务登录已失效，请重新登录")
        if response.status_code != 200:
            raise JwglxtError(
                f"课表页面返回异常状态：HTTP {response.status_code}"
            )

        soup = BeautifulSoup(response.text, "html.parser")
        term = _parse_selected_term(soup) or guess_current_term()
        return soup, term, _parse_terms(soup)

    def terms(self) -> tuple[Term, list[Term]]:
        _, current, options = self._load_timetable_page()
        return current, options or [current]

    def _post_json(self, path: str, form: dict[str, Any]) -> Any:
        response = self._request(
            "POST",
            path,
            data=form,
            xhr=True,
            allow_redirects=True,
        )
        if self._is_login_response(response):
            raise SessionExpired("教务登录已失效，请重新登录")
        if response.status_code != 200:
            raise JwglxtError(
                f"课表接口返回异常状态：HTTP {response.status_code}"
            )
        try:
            return response.json()
        except ValueError as exc:
            raise JwglxtError("课表接口没有返回有效 JSON") from exc

    def _fetch_periods(
        self,
        term: Term,
        campus_id: str,
    ) -> list[dict[str, Any]]:
        try:
            payload = self._post_json(
                PERIOD_QUERY,
                {
                    "xnm": term.xnm,
                    "xqm": term.xqm,
                    "xqh_id": campus_id,
                },
            )
            return _normalize_periods(payload)
        except SessionExpired:
            raise
        except JwglxtError:
            return _default_periods()

    def fetch_schedule(self, term: Term | str | None = None) -> dict[str, Any]:
        page, current_term, term_options = self._load_timetable_page()
        selected_term = Term.parse(term) if term else current_term

        payload = self._post_json(
            PERSONAL_TIMETABLE_QUERY,
            {
                "xnm": selected_term.xnm,
                "xqm": selected_term.xqm,
                "kzlx": "ck",
                "xsdm": "",
                "kclbdm": "",
                "kclxdm": "",
            },
        )
        raw_courses = _extract_course_rows(payload)
        if not raw_courses and isinstance(payload, dict):
            for value in payload.values():
                if isinstance(value, dict):
                    raw_courses.extend(_extract_course_rows(value))

        campus_id = ""
        for raw_course in raw_courses:
            campus_id = _first(raw_course, "xqh_id")
            if campus_id:
                break

        periods = self._fetch_periods(selected_term, campus_id)
        courses = _normalize_courses(raw_courses)
        today = date.today()
        week_number = (today - selected_term.start_date).days // 7 + 1
        current_week = min(20, max(1, week_number))

        student_name = ""
        if isinstance(payload, dict):
            student_info = payload.get("xsxx")
            if isinstance(student_info, dict):
                student_name = _first(student_info, "XM", "xm", "xsmc")
        if not student_name:
            name_node = page.find(id="xsmc")
            if name_node:
                student_name = str(
                    name_node.get("value") or name_node.get_text(strip=True)
                ).strip()

        return {
            "term": {
                "year": selected_term.year,
                "number": selected_term.number,
                "label": selected_term.label,
                "startDate": selected_term.start_date.isoformat(),
            },
            "availableTerms": [item.label for item in term_options],
            "currentWeek": current_week,
            "fetchedAt": datetime.now(timezone.utc)
            .replace(microsecond=0)
            .isoformat()
            .replace("+00:00", "Z"),
            "studentName": student_name,
            "periods": periods,
            "courses": courses,
            "source": {
                "baseUrl": self.base_url,
                "rawCourseCount": len(raw_courses),
            },
        }


def create_client_from_env() -> JwglxtClient:
    verify_tls = os.environ.get("JWXT_VERIFY_TLS", "1").lower() not in {
        "0",
        "false",
        "no",
    }
    return JwglxtClient(
        os.environ.get("JWXT_BASE_URL", DEFAULT_BASE_URL),
        cas_base_url=os.environ.get("CAS_BASE_URL", DEFAULT_CAS_BASE_URL),
        sso_url=os.environ.get("JWXT_SSO_URL", DEFAULT_SSO_URL),
        verify_tls=verify_tls,
    )
