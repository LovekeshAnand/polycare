package org.polycare.app.duelist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MonetizationOn
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.polycare.app.households.DueItem
import org.polycare.app.households.DuePriority
import org.polycare.app.households.MonthlyIncentiveReport
import org.polycare.app.households.VisitSearchResult
import org.polycare.app.households.VisitType
import org.polycare.app.ui.components.GlassCard
import org.polycare.app.ui.components.ScreenHeader
import org.polycare.app.ui.components.Hairline
import org.polycare.app.ui.components.LabelledField
import org.polycare.app.ui.components.SectionLabel
import org.polycare.app.ui.components.StatusPill
import org.polycare.app.ui.theme.Brand

private val Accent = Brand.Red

@Composable
fun DueListScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    viewModel: DueListViewModel = hiltViewModel(),
) {
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val dueItems by viewModel.filteredDueItems.collectAsStateWithLifecycle()
    val report by viewModel.report.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val allMembers by viewModel.members.collectAsStateWithLifecycle()
    val allVisits by viewModel.visits.collectAsStateWithLifecycle()

    var activeTab by remember { mutableIntStateOf(0) } // 0: Due Visits, 1: Search Notes, 2: Monthly Report
    var expandedDueId by remember { mutableStateOf<String?>(null) }
    var exportConfirmed by remember { mutableStateOf(false) }
    val exportContext = androidx.compose.ui.platform.LocalContext.current
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri != null) {
            exportConfirmed = runCatching {
                exportContext.contentResolver.openOutputStream(uri)?.use { it.write(viewModel.reportCsv().toByteArray()) } != null
            }.getOrDefault(false)
        }
    }

    LazyColumn(
        modifier = Modifier.statusBarsPadding(),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ScreenHeader("Due list & planner", Accent, onBack = onBack)
        }

        item {
            Spacer(Modifier.height(2.dp))
            Text("Today's visits", style = MaterialTheme.typography.displaySmall, color = Brand.Ink)
            Spacer(Modifier.height(4.dp))
            Text(
                "Prioritised daily schedule: due deliveries, missed vaccines, and home PNC visits.",
                style = MaterialTheme.typography.labelSmall,
                color = Brand.InkMuted,
            )
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SummaryCard("Pending", "${dueItems.size}", Accent, Modifier.weight(1f))
                SummaryCard("High Risk", "${dueItems.count { it.priority == DuePriority.HIGH }}", Brand.Rose, Modifier.weight(1f))
                SummaryCard("Earned", "₹${report.totalIncentiveRupees}", Brand.Positive, Modifier.weight(1f))
            }
        }

        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brand.Glass, MaterialTheme.shapes.large)
                    .border(1.dp, Brand.Line, MaterialTheme.shapes.large)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TabChip("Due (${dueItems.size})", active = activeTab == 0, Modifier.weight(1f)) { activeTab = 0 }
                TabChip("Search", active = activeTab == 1, Modifier.weight(1f)) { activeTab = 1 }
                TabChip("Report", active = activeTab == 2, Modifier.weight(1f)) { activeTab = 2 }
            }
        }

        when (activeTab) {
            0 -> {
                item {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DueFilter.entries.forEach { f ->
                            org.polycare.app.ui.components.ChoiceChip(f.label, selected = filter == f, onClick = { viewModel.setFilter(f) }, accent = Accent)
                        }
                    }
                }

                if (dueItems.isEmpty()) {
                    item {
                        GlassCard(Modifier.fillMaxWidth()) {
                            Text("All scheduled visits for this category are completed!", style = MaterialTheme.typography.bodyMedium, color = Brand.InkMuted)
                        }
                    }
                }

                items(dueItems, key = { it.id }) { item ->
                    DueItemCard(
                        item = item,
                        member = allMembers.firstOrNull { it.id == item.memberId },
                        lastVisit = allVisits
                            .filter { it.householdId == item.householdId && it.memberId == item.memberId }
                            .maxByOrNull { it.date },
                        expanded = expandedDueId == item.id,
                        onToggle = { expandedDueId = if (expandedDueId == item.id) null else item.id },
                        onComplete = { notes, highRisk ->
                            viewModel.recordDueVisit(item, notes, highRisk)
                            expandedDueId = null
                        },
                    )
                }
            }

            1 -> {
                item {
                    GlassCard(Modifier.fillMaxWidth(), accent = Brand.Magenta) {
                        SectionLabel("Semantic Visit Search", color = Brand.Magenta)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Search past visit notes by meaning (e.g. 'swollen feet', 'high fever', 'ORS follow-up'):",
                            style = MaterialTheme.typography.labelSmall,
                            color = Brand.InkMuted,
                        )
                        Spacer(Modifier.height(10.dp))
                        LabelledField("Search query", searchQuery) { viewModel.searchNotes(it) }
                    }
                }

                if (searchResults.isEmpty()) {
                    item {
                        GlassCard(Modifier.fillMaxWidth()) {
                            Text(
                                if (searchQuery.isBlank()) "No visit notes recorded yet. Record visits to search them." else "No visit notes matched '$searchQuery'.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Brand.InkMuted,
                            )
                        }
                    }
                }

                items(searchResults, key = { it.visit.id }) { result ->
                    VisitResultCard(result)
                }
            }

            2 -> {
                item {
                    MonthlyReportCard(
                        report = report,
                        exportConfirmed = exportConfirmed,
                        onExport = { exportLauncher.launch("asha-monthly-report.csv") },
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(label: String, value: String, accent: Color, modifier: Modifier) {
    GlassCard(modifier, accent = accent, padding = 12.dp) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = accent)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Brand.InkMuted)
    }
}

@Composable
private fun TabChip(label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .background(if (active) Accent else Color.Transparent, MaterialTheme.shapes.medium)
            .selectable(selected = active, role = androidx.compose.ui.semantics.Role.Tab, onClick = onClick)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (active) Brand.Paper else Brand.Ink, maxLines = 2)
    }
}

@Composable
private fun DueItemCard(
    item: DueItem,
    member: org.polycare.app.households.Member?,
    lastVisit: org.polycare.app.households.Visit?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onComplete: (String, Boolean) -> Unit,
) {
    val isHighRisk = item.priority == DuePriority.HIGH
    var notes by remember { mutableStateOf("") }
    var flagRisk by remember { mutableStateOf(isHighRisk) }

    GlassCard(
        Modifier.fillMaxWidth().clickable(onClickLabel = if (expanded) "Collapse visit" else "Open visit", role = androidx.compose.ui.semantics.Role.Button, onClick = onToggle),
        accent = if (isHighRisk) Brand.Rose else Accent,
    ) {
        // Stacked top to bottom so long names and villages wrap instead of being squeezed beside a pill.
        val overdue = (dueDay(item) ?: 0L) < 0
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(item.memberName, style = MaterialTheme.typography.titleMedium, color = Brand.Ink, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = Brand.InkMuted,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        StatusPill(
            "${item.visitType.label}${if (isHighRisk) " · High risk" else ""}",
            dot = if (isHighRisk) Brand.Rose else Accent,
        )
        Spacer(Modifier.height(10.dp))
        Text(dueLabel(item), style = MaterialTheme.typography.titleSmall, color = if (overdue) Brand.Red else Brand.Ink)
        Spacer(Modifier.height(4.dp))
        Text(item.reason, style = MaterialTheme.typography.bodyMedium, color = if (isHighRisk) Brand.Red else Brand.Ink)
        Spacer(Modifier.height(8.dp))
        val who = buildList {
            member?.let { add("Age ${it.age} · ${it.relation}") }
            add("Village: ${item.village}")
            if (item.householdHead.isNotBlank() && item.householdHead != item.memberName) add("Household of ${item.householdHead}")
        }
        who.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = Brand.InkMuted) }
        lastVisit?.let {
            Spacer(Modifier.height(8.dp))
            Text("Last visit ${it.date}: ${it.notes}", style = MaterialTheme.typography.bodyMedium, color = Brand.Ink, maxLines = 3)
        }

        if (expanded) {
            Spacer(Modifier.height(12.dp))
            Hairline()
            Spacer(Modifier.height(12.dp))

            SectionLabel("Record visit observations", color = Accent)
            Spacer(Modifier.height(8.dp))
            LabelledField("Visit notes (symptoms, advice, vitals)", notes, singleLine = false) { notes = it }
            Spacer(Modifier.height(10.dp))

            org.polycare.app.ui.components.ToggleRow("Mark as high risk: danger sign observed", flagRisk, { flagRisk = it }, accent = Brand.Red)

            Spacer(Modifier.height(10.dp))
            org.polycare.app.ui.components.PrimaryButton(
                "Complete visit & claim ₹${item.visitType.defaultIncentiveRupees}",
                onClick = { onComplete(notes.ifBlank { "Completed scheduled visit" }, flagRisk) },
                icon = Icons.Outlined.CheckCircle, accent = Accent,
            )
        }
    }
}

@Composable
private fun VisitResultCard(result: VisitSearchResult) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(result.visit.memberName ?: result.householdName, style = MaterialTheme.typography.titleSmall, color = Brand.Ink)
                Text("${result.visit.date} · ${result.village}", style = MaterialTheme.typography.labelSmall, color = Brand.InkMuted)
            }
            StatusPill(result.visit.type.label, dot = if (result.visit.highRisk) Brand.Rose else Brand.Positive)
        }
        Spacer(Modifier.height(8.dp))
        Text(result.visit.notes, style = MaterialTheme.typography.bodyMedium, color = Brand.Ink)
        if (result.visit.incentiveRupees > 0) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.MonetizationOn, contentDescription = null, tint = Brand.Positive, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Incentive: ₹${result.visit.incentiveRupees}", style = MaterialTheme.typography.labelSmall, color = Brand.Positive)
            }
        }
    }
}

@Composable
private fun MonthlyReportCard(
    report: MonthlyIncentiveReport,
    exportConfirmed: Boolean,
    onExport: () -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth(), accent = Brand.Positive) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Monthly ASHA Report", color = Brand.Positive)
            StatusPill(java.time.LocalDate.now().let { "${it.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)} ${it.year}" }, dot = Brand.Positive)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Auto-compiled from recorded field visits (NHM / JSY guidelines):",
            style = MaterialTheme.typography.labelSmall,
            color = Brand.InkMuted,
        )

        Spacer(Modifier.height(14.dp))
        IncentiveLine("ANC Check-ups motivated", "${report.ancCount} visits", "₹${report.ancCount * 300}")
        Hairline()
        IncentiveLine("PNC Newborn home visits", "${report.pncCount} visits", "₹${report.pncCount * 200}")
        Hairline()
        IncentiveLine("Immunization sessions mobilized", "${report.immunizationCount} visits", "₹${report.immunizationCount * 150}")
        Hairline()
        IncentiveLine("Family planning counselling", "${report.familyPlanningCount} visits", "₹${report.familyPlanningCount * 150}")
        Hairline()
        IncentiveLine("High-risk referrals identified", "${report.highRiskCount} cases", "Tracked")

        Spacer(Modifier.height(16.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .background(Brand.Positive.copy(alpha = 0.12f), MaterialTheme.shapes.large)
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Total ASHA Incentive", style = MaterialTheme.typography.titleMedium, color = Brand.Ink)
            Text("₹${report.totalIncentiveRupees}", style = MaterialTheme.typography.headlineSmall, color = Brand.Positive)
        }

        Spacer(Modifier.height(16.dp))
        if (exportConfirmed) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brand.Positive.copy(alpha = 0.12f), MaterialTheme.shapes.large)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = Brand.Positive, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Report saved. Share it at the PHC meeting.", style = MaterialTheme.typography.titleSmall, color = Brand.Positive)
            }
        } else {
            org.polycare.app.ui.components.PrimaryButton("Export summary for PHC meeting", onExport, accent = Brand.Positive)
            Spacer(Modifier.height(6.dp))
            Text(
                "Saves a spreadsheet of counts and incentives. It contains no names and no visit notes.",
                style = MaterialTheme.typography.labelSmall, color = Brand.InkMuted,
            )
        }
    }
}

@Composable
private fun IncentiveLine(label: String, countText: String, amountText: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Brand.Ink)
            Text(countText, style = MaterialTheme.typography.labelSmall, color = Brand.InkMuted)
        }
        Text(amountText, style = MaterialTheme.typography.titleSmall, color = Brand.Ink)
    }
}
