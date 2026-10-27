package com.gzus.courseassistant.data.sync

import android.app.Application
import com.gzus.courseassistant.data.local.AppSettings
import com.gzus.courseassistant.data.local.SecureFileStore
import com.gzus.courseassistant.data.model.AppState
import com.gzus.courseassistant.data.model.JsonCodec
import com.gzus.courseassistant.data.model.ScheduleSnapshot
import com.gzus.courseassistant.data.model.SyncSummary
import com.gzus.courseassistant.data.model.Term
import com.gzus.courseassistant.data.network.JwxtClient
import com.gzus.courseassistant.data.network.SessionExpiredException
import com.gzus.courseassistant.data.network.StoredCookieJar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

class AppRepository(
    private val application: Application,
    private val secureStore: SecureFileStore,
    private val cookieJar: StoredCookieJar,
    private val client: JwxtClient,
    private val settings: AppSettings,
) {
    private val stateMutex = Mutex()
    private val syncMutex = Mutex()
    private val _state = MutableStateFlow(
        JsonCodec.decodeState(secureStore.read(STATE_FILE)).copy(
            sessionValid = !cookieJar.isEmpty(),
        ),
    )
    val state: StateFlow<AppState> = _state.asStateFlow()

    val hasSession: Boolean
        get() = !cookieJar.isEmpty()

    suspend fun fetchCaptcha() = withContext(Dispatchers.IO) {
        client.beginCasSession()
        client.fetchCaptcha()
    }

    suspend fun login(
        username: String,
        password: String,
        captchaId: String,
        captchaCode: String,
    ) = withContext(Dispatchers.IO) {
        client.login(username, password, captchaId, captchaCode)
        stateMutex.withLock {
            saveStateLocked(_state.value.copy(sessionValid = !cookieJar.isEmpty()))
        }
    }

    suspend fun syncAll(
        forceCalendar: Boolean = false,
        termLabel: String? = null,
    ): SyncSummary = withContext(Dispatchers.IO) {
        try {
            syncMutex.withLock {
                var scheduleUpdated = false
                var gradesUpdated = false
                var examsUpdated = false
                var calendarUpdated = false
                var messages = mutableListOf<String>()

                val schedule = client.fetchSchedule(termLabel)
                scheduleUpdated = true
                saveSchedule(
                    schedule = schedule,
                    availableTerms = client.lastAvailableTerms,
                    markAsCurrentTerm = termLabel == null,
                )

                try {
                    val grades = client.fetchGrades(schedule.term)
                    val exams = client.fetchExams(schedule.term)
                    saveAcademics(schedule.term, grades, exams)
                    gradesUpdated = true
                    examsUpdated = true
                } catch (error: SessionExpiredException) {
                    throw error
                } catch (error: Exception) {
                    messages += "成绩/考试未更新：${error.message.orEmpty()}"
                }

                if (forceCalendar || isCalendarStale()) {
                    calendarUpdated = syncCalendarLocked()
                    if (!calendarUpdated) messages += "校历暂时无法更新"
                }

                SyncSummary(
                    scheduleUpdated = scheduleUpdated,
                    gradesUpdated = gradesUpdated,
                    examsUpdated = examsUpdated,
                    calendarUpdated = calendarUpdated,
                    message = if (messages.isEmpty()) "同步完成" else messages.joinToString("\n"),
                )
            }
        } catch (error: SessionExpiredException) {
            invalidateSession()
            throw error
        }
    }

    suspend fun syncAcademics(term: Term): SyncSummary = withContext(Dispatchers.IO) {
        try {
            syncMutex.withLock {
                val grades = client.fetchGrades(term)
                val exams = client.fetchExams(term)
                saveAcademics(term, grades, exams)
                SyncSummary(
                    scheduleUpdated = false,
                    gradesUpdated = true,
                    examsUpdated = true,
                    calendarUpdated = false,
                    message = "学业信息已更新",
                )
            }
        } catch (error: SessionExpiredException) {
            invalidateSession()
            throw error
        }
    }

    suspend fun syncCalendar(force: Boolean = false): SyncSummary = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            if (!force && !isCalendarStale()) {
                return@withLock SyncSummary(
                    scheduleUpdated = false,
                    gradesUpdated = false,
                    examsUpdated = false,
                    calendarUpdated = false,
                    message = "校历缓存仍在有效期内",
                )
            }
            val updated = syncCalendarLocked()
            SyncSummary(
                scheduleUpdated = false,
                gradesUpdated = false,
                examsUpdated = false,
                calendarUpdated = updated,
                message = if (updated) "校历已更新" else "校历更新失败",
            )
        }
    }

    suspend fun selectTerm(label: String) = withContext(Dispatchers.Default) {
        stateMutex.withLock {
            saveStateLocked(_state.value.copy(selectedTerm = label))
        }
    }

    suspend fun setFirstWeekStart(term: Term, date: LocalDate) =
        withContext(Dispatchers.Default) {
            val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            stateMutex.withLock {
                val current = _state.value
                saveStateLocked(
                    current.copy(
                        firstWeekStarts = current.firstWeekStarts + (term.label to monday),
                    ),
                )
            }
        }

    suspend fun clearFirstWeekStart(label: String) = withContext(Dispatchers.Default) {
        stateMutex.withLock {
            val current = _state.value
            saveStateLocked(
                current.copy(firstWeekStarts = current.firstWeekStarts - label),
            )
        }
    }

    suspend fun logout(clearData: Boolean) = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            cookieJar.clear()
            stateMutex.withLock {
                if (clearData) {
                    secureStore.clear()
                    File(application.filesDir, "calendar").deleteRecursively()
                    settings.clearAll()
                    _state.value = AppState()
                } else {
                    saveStateLocked(_state.value.copy(sessionValid = false))
                }
            }
        }
    }

    suspend fun markSessionUnavailable() = invalidateSession()

    private suspend fun saveSchedule(
        schedule: ScheduleSnapshot,
        availableTerms: List<Term>,
        markAsCurrentTerm: Boolean,
    ) {
        stateMutex.withLock {
            val current = _state.value
            val schedules = current.schedules
                .filterNot { it.term.label == schedule.term.label } + schedule
            saveStateLocked(
                current.copy(
                    selectedTerm = schedule.term.label,
                    currentTerm = if (markAsCurrentTerm) {
                        schedule.term.label
                    } else {
                        current.currentTerm
                    },
                    sessionValid = true,
                    availableTerms = availableTerms.ifEmpty { current.availableTerms },
                    schedules = schedules,
                    lastScheduleSync = System.currentTimeMillis(),
                    studentName = client.lastStudentName,
                ),
            )
        }
    }

    private suspend fun saveAcademics(
        term: Term,
        grades: List<com.gzus.courseassistant.data.model.Grade>,
        exams: List<com.gzus.courseassistant.data.model.Exam>,
    ) {
        stateMutex.withLock {
            val current = _state.value
            saveStateLocked(
                current.copy(
                    sessionValid = true,
                    grades = current.grades.filterNot { it.term == term } + grades,
                    exams = current.exams.filterNot { it.term == term } + exams,
                    lastAcademicSync = System.currentTimeMillis(),
                ),
            )
        }
    }

    private suspend fun syncCalendarLocked(): Boolean {
        return try {
            val entries = client.fetchCalendar().map { entry ->
                try {
                    client.downloadCalendarImage(
                        entry,
                        File(application.filesDir, "calendar"),
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    entry
                }
            }
            if (entries.isEmpty()) {
                false
            } else {
                stateMutex.withLock {
                    saveStateLocked(
                        _state.value.copy(
                            calendarEntries = entries,
                            lastCalendarSync = System.currentTimeMillis(),
                        ),
                    )
                }
                true
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            false
        }
    }

    private fun isCalendarStale(): Boolean {
        val last = _state.value.lastCalendarSync
        return System.currentTimeMillis() - last > TimeUnit.DAYS.toMillis(7)
    }

    private suspend fun invalidateSession() {
        stateMutex.withLock {
            cookieJar.clear()
            val current = _state.value
            if (current.sessionValid) {
                saveStateLocked(current.copy(sessionValid = false))
            }
        }
    }

    private fun saveStateLocked(state: AppState) {
        secureStore.write(STATE_FILE, JsonCodec.encodeState(state))
        _state.value = state
    }

    private companion object {
        const val STATE_FILE = "state.json"
    }
}
