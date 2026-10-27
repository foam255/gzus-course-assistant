const elements = {
  syncState: document.querySelector("#syncState"),
  syncButton: document.querySelector("#syncButton"),
  sessionButton: document.querySelector("#sessionButton"),
  termLabel: document.querySelector("#termLabel"),
  syncDetail: document.querySelector("#syncDetail"),
  errorBanner: document.querySelector("#errorBanner"),
  errorText: document.querySelector("#errorText"),
  dismissError: document.querySelector("#dismissError"),
  loadingPanel: document.querySelector("#loadingPanel"),
  emptyPanel: document.querySelector("#emptyPanel"),
  scheduleView: document.querySelector("#scheduleView"),
  emptyLoginButton: document.querySelector("#emptyLoginButton"),
  previousWeek: document.querySelector("#previousWeek"),
  currentWeekButton: document.querySelector("#currentWeekButton"),
  nextWeek: document.querySelector("#nextWeek"),
  todayButton: document.querySelector("#todayButton"),
  courseCount: document.querySelector("#courseCount"),
  studentName: document.querySelector("#studentName"),
  weekRange: document.querySelector("#weekRange"),
  desktopGrid: document.querySelector("#desktopGrid"),
  dayTabs: document.querySelector("#dayTabs"),
  mobileCourseList: document.querySelector("#mobileCourseList"),
  loginDialog: document.querySelector("#loginDialog"),
  loginForm: document.querySelector("#loginForm"),
  closeLoginButton: document.querySelector("#closeLoginButton"),
  username: document.querySelector("#username"),
  password: document.querySelector("#password"),
  captchaCode: document.querySelector("#captchaCode"),
  captchaImage: document.querySelector("#captchaImage"),
  captchaPlaceholder: document.querySelector("#captchaPlaceholder"),
  refreshCaptcha: document.querySelector("#refreshCaptcha"),
  term: document.querySelector("#term"),
  rememberSession: document.querySelector("#rememberSession"),
  togglePassword: document.querySelector("#togglePassword"),
  loginError: document.querySelector("#loginError"),
  loginButton: document.querySelector("#loginButton"),
  toast: document.querySelector("#toast"),
};

const dayNames = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"];
const colors = [
  { bg: "#e8f3f1", border: "#beded8", accent: "#0f766e" },
  { bg: "#fff1eb", border: "#f3c9b6", accent: "#d95d39" },
  { bg: "#eef2fb", border: "#cbd7ef", accent: "#3f5f9f" },
  { bg: "#fff7dc", border: "#e9d995", accent: "#a56d06" },
  { bg: "#f5edf7", border: "#dfc8e5", accent: "#8c4b9d" },
  { bg: "#eef5e8", border: "#cfe0c2", accent: "#537b3d" },
  { bg: "#fdeef1", border: "#efc8d0", accent: "#a33b55" },
];

const state = {
  data: null,
  week: 1,
  day: new Date().getDay() || 7,
  loading: false,
  loginLoading: false,
  toastTimer: null,
  captchaId: "",
  captchaLoading: false,
};

class ApiError extends Error {
  constructor(message, status, code) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
  }
}

function refreshIcons() {
  if (window.lucide && typeof window.lucide.createIcons === "function") {
    window.lucide.createIcons({
      attrs: {
        "aria-hidden": "true",
      },
    });
  }
}

async function api(path, options = {}) {
  const requestOptions = { ...options };
  requestOptions.headers = {
    Accept: "application/json",
    ...(options.headers || {}),
  };
  if (options.body && !requestOptions.headers["Content-Type"]) {
    requestOptions.headers["Content-Type"] = "application/json";
  }

  const response = await fetch(path, requestOptions);
  let payload = null;
  try {
    payload = await response.json();
  } catch {
    payload = null;
  }

  if (!response.ok) {
    const error = new ApiError(
      payload?.error || `请求失败（HTTP ${response.status}）`,
      response.status,
      payload?.code || "request_failed",
    );
    error.details = payload;
    throw error;
  }
  return payload;
}

function showError(message) {
  elements.errorText.textContent = message;
  elements.errorBanner.hidden = false;
}

function clearError() {
  elements.errorBanner.hidden = true;
  elements.errorText.textContent = "";
}

function showToast(message) {
  window.clearTimeout(state.toastTimer);
  elements.toast.textContent = message;
  elements.toast.hidden = false;
  state.toastTimer = window.setTimeout(() => {
    elements.toast.hidden = true;
  }, 2600);
}

function setSyncBusy(isBusy) {
  elements.syncButton.disabled = isBusy;
  const icon = isBusy ? "loader-circle" : "refresh-cw";
  elements.syncButton.innerHTML = `<i data-lucide="${icon}"></i><span>${
    isBusy ? "同步中" : "同步"
  }</span>`;
  if (isBusy) {
    elements.syncButton.querySelector("svg")?.classList.add("spin");
  }
  refreshIcons();
}

function setLoginBusy(isBusy) {
  state.loginLoading = isBusy;
  elements.loginButton.disabled = isBusy;
  elements.refreshCaptcha.disabled = isBusy;
  elements.loginButton.innerHTML = `<i data-lucide="${
    isBusy ? "loader-circle" : "log-in"
  }"></i><span>${isBusy ? "正在连接" : "登录并同步"}</span>`;
  refreshIcons();
}

function openLogin() {
  elements.loginError.hidden = true;
  elements.loginError.textContent = "";
  if (!elements.loginDialog.open) {
    elements.loginDialog.showModal();
  }
  loadCaptcha();
  window.setTimeout(() => elements.username.focus(), 50);
}

function closeLogin() {
  if (state.loginLoading) {
    return;
  }
  elements.loginDialog.close();
}

function applyCaptcha(captcha) {
  if (!captcha?.captchaId || !captcha?.image) {
    return;
  }
  state.captchaId = captcha.captchaId;
  elements.captchaCode.value = "";
  elements.captchaImage.src = captcha.image;
  elements.captchaPlaceholder.textContent = "刷新";
}

async function loadCaptcha() {
  if (state.captchaLoading || state.loginLoading) {
    return;
  }
  state.captchaLoading = true;
  elements.refreshCaptcha.disabled = true;
  elements.refreshCaptcha.classList.add("is-loading");
  elements.captchaPlaceholder.textContent = "加载";
  try {
    const captcha = await api("/api/captcha");
    applyCaptcha(captcha);
  } catch (error) {
    elements.loginError.textContent = error.message;
    elements.loginError.hidden = false;
    state.captchaId = "";
    elements.captchaImage.removeAttribute("src");
    elements.captchaPlaceholder.textContent = "重试";
  } finally {
    state.captchaLoading = false;
    elements.refreshCaptcha.disabled = state.loginLoading;
    elements.refreshCaptcha.classList.remove("is-loading");
    refreshIcons();
  }
}

function formatDateTime(value) {
  if (!value) {
    return "尚未同步";
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return "尚未同步";
  }
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(date);
}

function formatTerm(term) {
  if (!term?.label) {
    return "当前学期";
  }
  const parts = term.label.split("-");
  if (parts.length !== 3) {
    return term.label;
  }
  const semester = parts[2] === "1" ? "第一学期" : "第二学期";
  return `${parts[0]}-${parts[1]} 学年 · ${semester}`;
}

function dateFromIso(value, offsetDays = 0) {
  const date = new Date(`${value}T00:00:00`);
  date.setDate(date.getDate() + offsetDays);
  return date;
}

function dayDate(weekday) {
  if (!state.data?.term?.startDate) {
    return null;
  }
  const offset = (state.week - 1) * 7 + weekday - 1;
  return dateFromIso(state.data.term.startDate, offset);
}

function shortDate(date) {
  return date
    ? `${date.getMonth() + 1}/${date.getDate()}`
    : "";
}

function sameMonthDay(left, right) {
  return (
    left &&
    right &&
    left.getFullYear() === right.getFullYear() &&
    left.getMonth() === right.getMonth() &&
    left.getDate() === right.getDate()
  );
}

function coursePalette(course) {
  let hash = 0;
  for (const character of course.name || "") {
    hash = (hash * 31 + character.charCodeAt(0)) >>> 0;
  }
  return colors[hash % colors.length];
}

function formatPeriodRange(course) {
  if (course.startPeriod === course.endPeriod) {
    return `第 ${course.startPeriod} 节`;
  }
  return `第 ${course.startPeriod}-${course.endPeriod} 节`;
}

function periodTime(course) {
  const periodMap = new Map(
    (state.data?.periods || []).map((period) => [period.index, period]),
  );
  const first = periodMap.get(course.startPeriod);
  const last = periodMap.get(course.endPeriod);
  if (first?.start && last?.end) {
    return `${first.start}-${last.end}`;
  }
  return "";
}

function activeCourses() {
  return (state.data?.courses || []).filter((course) =>
    course.weeks.includes(state.week),
  );
}

function assignConflictLanes(courses) {
  const layout = new Map();
  const byDay = new Map();
  for (const course of courses) {
    if (!byDay.has(course.weekday)) {
      byDay.set(course.weekday, []);
    }
    byDay.get(course.weekday).push(course);
  }

  for (const dayCourses of byDay.values()) {
    dayCourses.sort(
      (left, right) =>
        left.startPeriod - right.startPeriod ||
        left.endPeriod - right.endPeriod ||
        left.name.localeCompare(right.name, "zh-CN"),
    );

    const groups = [];
    let currentGroup = [];
    let groupEnd = 0;
    for (const course of dayCourses) {
      if (currentGroup.length && course.startPeriod > groupEnd) {
        groups.push(currentGroup);
        currentGroup = [];
        groupEnd = 0;
      }
      currentGroup.push(course);
      groupEnd = Math.max(groupEnd, course.endPeriod);
    }
    if (currentGroup.length) {
      groups.push(currentGroup);
    }

    for (const group of groups) {
      const laneEnds = [];
      for (const course of group) {
        let lane = laneEnds.findIndex((end) => end < course.startPeriod);
        if (lane === -1) {
          lane = laneEnds.length;
        }
        laneEnds[lane] = course.endPeriod;
        layout.set(course.id, { lane, laneCount: 0 });
      }
      for (const course of group) {
        layout.get(course.id).laneCount = laneEnds.length;
      }
    }
  }
  return layout;
}

function makeCourseBlock(course, layout) {
  const palette = coursePalette(course);
  const block = document.createElement("article");
  block.className = "course-block";
  block.style.gridColumn = String(course.weekday + 1);
  block.style.gridRow = `${course.startPeriod + 1} / ${course.endPeriod + 2}`;
  block.style.setProperty("--course-bg", palette.bg);
  block.style.setProperty("--course-border", palette.border);
  block.style.setProperty("--course-accent", palette.accent);
  block.title = [
    course.name,
    course.teacher,
    course.room,
    formatPeriodRange(course),
    course.weekText,
  ]
    .filter(Boolean)
    .join(" · ");

  const lane = layout.get(course.id);
  if (lane?.laneCount > 1) {
    const width = 100 / lane.laneCount;
    block.style.width = `calc(${width}% - 6px)`;
    block.style.marginLeft = `calc(${lane.lane * width}% + 3px)`;
  }

  const name = document.createElement("div");
  name.className = "course-name";
  name.textContent = course.name;
  block.append(name);

  if (course.room) {
    const room = document.createElement("div");
    room.className = "course-meta";
    room.innerHTML = '<i data-lucide="map-pin"></i>';
    room.append(document.createTextNode(course.room));
    block.append(room);
  }

  if (course.teacher) {
    const teacher = document.createElement("div");
    teacher.className = "course-meta";
    teacher.innerHTML = '<i data-lucide="user-round"></i>';
    teacher.append(document.createTextNode(course.teacher));
    block.append(teacher);
  }

  if (course.endPeriod - course.startPeriod >= 1 && course.weekText) {
    const weeks = document.createElement("div");
    weeks.className = "course-weeks";
    weeks.textContent = course.weekText;
    block.append(weeks);
  }

  return block;
}

function renderDesktop(courses) {
  const periods = state.data.periods?.length
    ? state.data.periods
    : Array.from({ length: 12 }, (_, index) => ({
        index: index + 1,
        label: String(index + 1),
        start: "",
        end: "",
      }));
  const maxPeriod = Math.max(
    12,
    ...periods.map((period) => Number(period.index)),
    ...courses.map((course) => course.endPeriod),
  );
  const periodMap = new Map(periods.map((period) => [period.index, period]));
  elements.desktopGrid.style.setProperty("--period-count", String(maxPeriod));
  elements.desktopGrid.replaceChildren();

  const fragment = document.createDocumentFragment();
  const corner = document.createElement("div");
  corner.className = "corner-cell";
  corner.textContent = "节次";
  fragment.append(corner);

  const today = new Date();
  for (let weekday = 1; weekday <= 7; weekday += 1) {
    const date = dayDate(weekday);
    const header = document.createElement("div");
    header.className = "day-head";
    header.style.gridColumn = String(weekday + 1);
    if (sameMonthDay(date, today)) {
      header.classList.add("is-today");
    }
    const name = document.createElement("strong");
    name.textContent = dayNames[weekday - 1];
    const dateLabel = document.createElement("span");
    dateLabel.textContent = shortDate(date);
    header.append(name, dateLabel);
    fragment.append(header);
  }

  for (let index = 1; index <= maxPeriod; index += 1) {
    const period = periodMap.get(index) || {
      index,
      label: String(index),
      start: "",
      end: "",
    };
    const label = document.createElement("div");
    label.className = "period-label";
    label.style.gridColumn = "1";
    label.style.gridRow = String(index + 1);
    const strong = document.createElement("strong");
    strong.textContent = period.label || String(index);
    const time = document.createElement("span");
    time.textContent = period.start && period.end ? period.start : "";
    label.append(strong, time);
    fragment.append(label);

    for (let weekday = 1; weekday <= 7; weekday += 1) {
      const cell = document.createElement("div");
      cell.className = "grid-cell";
      cell.style.gridColumn = String(weekday + 1);
      cell.style.gridRow = String(index + 1);
      fragment.append(cell);
    }
  }

  const layout = assignConflictLanes(courses);
  for (const course of courses) {
    fragment.append(makeCourseBlock(course, layout));
  }
  elements.desktopGrid.append(fragment);
}

function renderMobile(courses) {
  const today = new Date();
  const counts = new Map();
  for (const course of courses) {
    counts.set(course.weekday, (counts.get(course.weekday) || 0) + 1);
  }

  elements.dayTabs.replaceChildren();
  for (let weekday = 1; weekday <= 7; weekday += 1) {
    const date = dayDate(weekday);
    const button = document.createElement("button");
    button.type = "button";
    button.className = "day-tab";
    button.setAttribute("role", "tab");
    button.setAttribute("aria-selected", String(weekday === state.day));
    button.dataset.weekday = String(weekday);
    if (weekday === state.day) {
      button.classList.add("is-active");
    }
    if (sameMonthDay(date, today)) {
      button.classList.add("is-today");
    }

    const name = document.createElement("span");
    name.textContent = dayNames[weekday - 1].slice(1);
    const strong = document.createElement("strong");
    strong.textContent = shortDate(date);
    button.append(name, strong);

    if (counts.get(weekday)) {
      button.title = `${counts.get(weekday)} 门课`;
    }
    elements.dayTabs.append(button);
  }

  const dayCourses = courses
    .filter((course) => course.weekday === state.day)
    .sort(
      (left, right) =>
        left.startPeriod - right.startPeriod ||
        left.name.localeCompare(right.name, "zh-CN"),
    );
  elements.mobileCourseList.replaceChildren();

  if (!dayCourses.length) {
    const empty = document.createElement("div");
    empty.className = "mobile-empty";
    empty.textContent = "这一天没有课程";
    elements.mobileCourseList.append(empty);
    return;
  }

  for (const course of dayCourses) {
    const palette = coursePalette(course);
    const card = document.createElement("article");
    card.className = "mobile-course";
    card.style.setProperty("--course-bg", palette.bg);
    card.style.setProperty("--course-border", palette.border);
    card.style.setProperty("--course-accent", palette.accent);

    const time = document.createElement("div");
    time.className = "mobile-time";
    const period = document.createElement("strong");
    period.textContent = `${course.startPeriod}-${course.endPeriod}`;
    const clock = document.createElement("span");
    clock.textContent = periodTime(course) || "节次";
    time.append(period, clock);

    const content = document.createElement("div");
    const title = document.createElement("h3");
    title.textContent = course.name;
    content.append(title);

    if (course.room) {
      const room = document.createElement("p");
      room.textContent = course.room;
      content.append(room);
    }
    if (course.teacher) {
      const teacher = document.createElement("p");
      teacher.textContent = course.teacher;
      content.append(teacher);
    }
    if (course.weekText) {
      const weeks = document.createElement("p");
      weeks.textContent = course.weekText;
      content.append(weeks);
    }

    card.append(time, content);
    elements.mobileCourseList.append(card);
  }
}

function render() {
  if (!state.data) {
    elements.scheduleView.hidden = true;
    elements.emptyPanel.hidden = false;
    elements.syncState.textContent = "需要登录";
    refreshIcons();
    return;
  }

  const maxWeek = 20;
  state.week = Math.min(maxWeek, Math.max(1, state.week));
  const courses = activeCourses();
  elements.emptyPanel.hidden = true;
  elements.loadingPanel.hidden = true;
  elements.scheduleView.hidden = false;
  elements.termLabel.textContent = formatTerm(state.data.term);
  elements.syncDetail.textContent = `最近同步：${formatDateTime(
    state.data.fetchedAt,
  )}`;
  elements.syncState.textContent = "本地课表已就绪";
  elements.currentWeekButton.textContent = `第 ${state.week} 周`;
  elements.previousWeek.disabled = state.week <= 1;
  elements.nextWeek.disabled = state.week >= maxWeek;
  elements.courseCount.textContent = String(courses.length);
  elements.studentName.textContent = state.data.studentName || "已连接";

  const monday = dayDate(1);
  const sunday = dayDate(7);
  elements.weekRange.textContent =
    monday && sunday
      ? `${shortDate(monday)} - ${shortDate(sunday)}`
      : "日期未知";

  renderDesktop(courses);
  renderMobile(courses);
  refreshIcons();
}

async function loadInitialData() {
  elements.loadingPanel.hidden = false;
  elements.emptyPanel.hidden = true;
  elements.scheduleView.hidden = true;

  try {
    const [scheduleResult, statusResult] = await Promise.allSettled([
      api("/api/schedule"),
      api("/api/status"),
    ]);

    if (scheduleResult.status === "fulfilled") {
      state.data = scheduleResult.value;
      state.week = scheduleResult.value.currentWeek || 1;
      render();
    } else {
      state.data = null;
      elements.loadingPanel.hidden = true;
      render();
    }
    if (
      statusResult.status === "fulfilled" &&
      statusResult.value.hasSavedSession
    ) {
      window.setTimeout(() => syncSchedule({ silent: true }), 700);
    }
  } catch (error) {
    elements.loadingPanel.hidden = true;
    state.data = null;
    render();
    showError(error.message);
  }
}

async function syncSchedule({ silent = false } = {}) {
  if (state.loading) {
    return;
  }
  state.loading = true;
  setSyncBusy(true);
  if (!silent) {
    clearError();
  }

  try {
    const result = await api("/api/sync", {
      method: "POST",
      body: JSON.stringify({}),
    });
    state.data = result.schedule;
    state.week = result.schedule.currentWeek || state.week || 1;
    render();
    if (!silent) {
      showToast("课程已同步");
    }
  } catch (error) {
    if (error.code === "login_required" || error.code === "session_expired") {
      if (!state.data) {
        elements.emptyPanel.hidden = false;
      }
      openLogin();
      showError(error.message);
    } else {
      showError(error.message);
    }
  } finally {
    state.loading = false;
    setSyncBusy(false);
  }
}

async function handleLogin(event) {
  event.preventDefault();
  if (state.loginLoading) {
    return;
  }

  elements.loginError.hidden = true;
  setLoginBusy(true);
  const formData = new FormData(elements.loginForm);
  const payload = {
    username: String(formData.get("username") || "").trim(),
    password: String(formData.get("password") || ""),
    captchaId: state.captchaId,
    captchaCode: String(formData.get("captchaCode") || "").trim(),
    term: String(formData.get("term") || "").trim(),
    rememberSession: elements.rememberSession.checked,
  };

  try {
    const result = await api("/api/login", {
      method: "POST",
      body: JSON.stringify(payload),
    });
    state.data = result.schedule;
    state.week = result.schedule.currentWeek || 1;
    state.day = new Date().getDay() || 7;
    clearError();
    render();
    elements.password.value = "";
    elements.loginDialog.close();
    showToast("登录成功，课表已保存到本地");
  } catch (error) {
    elements.loginError.textContent = error.message;
    elements.loginError.hidden = false;
    if (error.details?.captcha) {
      applyCaptcha(error.details.captcha);
    } else {
      state.captchaId = "";
      loadCaptcha();
    }
  } finally {
    setLoginBusy(false);
  }
}

function changeWeek(delta) {
  state.week = Math.min(20, Math.max(1, state.week + delta));
  render();
}

function bindEvents() {
  elements.syncButton.addEventListener("click", () => syncSchedule());
  elements.emptyLoginButton.addEventListener("click", openLogin);
  elements.sessionButton.addEventListener("click", openLogin);
  elements.loginForm.addEventListener("submit", handleLogin);
  elements.closeLoginButton.addEventListener("click", closeLogin);
  elements.refreshCaptcha.addEventListener("click", () => {
    elements.loginError.hidden = true;
    elements.loginError.textContent = "";
    loadCaptcha();
  });
  elements.dismissError.addEventListener("click", clearError);
  elements.previousWeek.addEventListener("click", () => changeWeek(-1));
  elements.nextWeek.addEventListener("click", () => changeWeek(1));
  elements.todayButton.addEventListener("click", () => {
    state.week = state.data?.currentWeek || 1;
    state.day = new Date().getDay() || 7;
    render();
  });
  elements.currentWeekButton.addEventListener("click", () => {
    state.week = state.data?.currentWeek || 1;
    render();
  });

  elements.togglePassword.addEventListener("click", () => {
    const show = elements.password.type === "password";
    elements.password.type = show ? "text" : "password";
    elements.togglePassword.setAttribute("aria-label", show ? "隐藏密码" : "显示密码");
    elements.togglePassword.title = show ? "隐藏密码" : "显示密码";
    elements.togglePassword.innerHTML = `<i data-lucide="${
      show ? "eye-off" : "eye"
    }"></i>`;
    refreshIcons();
  });

  elements.dayTabs.addEventListener("click", (event) => {
    const button = event.target.closest("[data-weekday]");
    if (!button) {
      return;
    }
    state.day = Number(button.dataset.weekday);
    renderMobile(activeCourses());
    refreshIcons();
  });

  elements.loginDialog.addEventListener("click", (event) => {
    if (event.target === elements.loginDialog) {
      closeLogin();
    }
  });
}

bindEvents();
refreshIcons();
loadInitialData();

window.setInterval(() => {
  if (state.data && !document.hidden) {
    syncSchedule({ silent: true });
  }
}, 30 * 60 * 1000);
