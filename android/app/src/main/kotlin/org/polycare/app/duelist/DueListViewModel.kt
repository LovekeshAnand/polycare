package org.polycare.app.duelist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.polycare.app.households.DueItem
import org.polycare.app.households.DuePriority
import org.polycare.app.households.HouseholdsRepository
import org.polycare.app.households.MonthlyIncentiveReport
import org.polycare.app.households.Visit
import org.polycare.app.households.VisitSearchResult
import org.polycare.app.households.VisitType
import javax.inject.Inject

/** Days from today until [item] is due (negative = overdue); null for a legacy label instead of a date. */
fun dueDay(item: DueItem, today: java.time.LocalDate = java.time.LocalDate.now()): Long? =
    runCatching { java.time.temporal.ChronoUnit.DAYS.between(today, java.time.LocalDate.parse(item.dueDate)) }.getOrNull()

/** "Overdue by 3 days", "Due today", "Tomorrow", "In 5 days" — derived from the real date every time. */
fun dueLabel(item: DueItem): String = when (val d = dueDay(item)) {
    null -> item.dueDate
    0L -> "Due today"
    1L -> "Tomorrow"
    else -> if (d < 0) "Overdue by ${-d} day${if (-d == 1L) "" else "s"}" else "In $d days"
}

enum class DueFilter(val label: String) {
    ALL("All Due"),
    HIGH_RISK("High Risk"),
    ANC("ANC"),
    PNC("PNC"),
    IMMUNIZATION("Immunization"),
}

@HiltViewModel
class DueListViewModel @Inject constructor(
    private val repo: HouseholdsRepository,
) : ViewModel() {

    private val _filter = MutableStateFlow(DueFilter.ALL)
    val filter: StateFlow<DueFilter> = _filter.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<VisitSearchResult>>(emptyList())
    val searchResults: StateFlow<List<VisitSearchResult>> = _searchResults.asStateFlow()

    val visits: StateFlow<List<Visit>> = repo.visits
    val members: StateFlow<List<org.polycare.app.households.Member>> = repo.members

    val filteredDueItems: StateFlow<List<DueItem>> = combine(repo.dueItems, _filter) { items, currentFilter ->
        val pending = items.filter { !it.completed }.sortedWith(compareBy<DueItem> { it.priority != DuePriority.HIGH }.thenBy { dueDay(it) ?: Long.MAX_VALUE })
        when (currentFilter) {
            DueFilter.ALL -> pending
            DueFilter.HIGH_RISK -> pending.filter { it.priority == DuePriority.HIGH }
            DueFilter.ANC -> pending.filter { it.visitType == VisitType.ANC }
            DueFilter.PNC -> pending.filter { it.visitType == VisitType.PNC }
            DueFilter.IMMUNIZATION -> pending.filter { it.visitType == VisitType.IMMUNIZATION }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val report: StateFlow<MonthlyIncentiveReport> = combine(repo.visits) {
        repo.monthlyReport()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), repo.monthlyReport())

    init {
        searchNotes("")
    }

    /**
     * Monthly summary for the PHC meeting as CSV: counts and incentive amounts per visit type, then
     * one row per visit with its date and type. Deliberately no names and no visit notes.
     */
    fun reportCsv(): String {
        val r = repo.monthlyReport()
        return buildString {
            appendLine("Monthly ASHA report")
            appendLine("visit_type,visits,incentive_rupees")
            appendLine("ANC,${r.ancCount},${r.ancCount * VisitType.ANC.defaultIncentiveRupees}")
            appendLine("PNC,${r.pncCount},${r.pncCount * VisitType.PNC.defaultIncentiveRupees}")
            appendLine("Immunization,${r.immunizationCount},${r.immunizationCount * VisitType.IMMUNIZATION.defaultIncentiveRupees}")
            appendLine("Family planning,${r.familyPlanningCount},${r.familyPlanningCount * VisitType.FAMILY_PLANNING.defaultIncentiveRupees}")
            appendLine("High-risk cases identified,${r.highRiskCount},")
            appendLine("Total,${r.totalVisits},${r.totalIncentiveRupees}")
            appendLine()
            appendLine("date,visit_type,high_risk,incentive_rupees")
            r.visits.forEach { v -> appendLine("${v.date},${v.type.label},${v.highRisk},${v.incentiveRupees}") }
        }
    }

    fun setFilter(filter: DueFilter) {
        _filter.value = filter
    }

    fun searchNotes(query: String) {
        _searchQuery.value = query
        viewModelScope.launch {
            _searchResults.value = repo.searchVisits(query)
        }
    }

    fun recordDueVisit(dueItem: DueItem, notes: String, highRisk: Boolean = false) {
        repo.recordVisit(
            householdId = dueItem.householdId,
            memberId = dueItem.memberId,
            memberName = dueItem.memberName,
            type = dueItem.visitType,
            notes = notes,
            highRisk = highRisk || dueItem.priority == DuePriority.HIGH,
            dueItemId = dueItem.id,
        )
        // Refresh search results
        searchNotes(_searchQuery.value)
    }

    fun recordAdHocVisit(
        householdId: String,
        memberName: String,
        type: VisitType,
        notes: String,
        highRisk: Boolean,
    ): Boolean {
        val visit = repo.recordVisit(
            householdId = householdId,
            memberId = null,
            memberName = memberName,
            type = type,
            notes = notes,
            highRisk = highRisk,
        )
        searchNotes(_searchQuery.value)
        return visit != null
    }
}
