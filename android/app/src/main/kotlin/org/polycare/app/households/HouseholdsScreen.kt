package org.polycare.app.households

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.polycare.app.ui.components.AppIconButton
import org.polycare.app.ui.components.ChipRow
import org.polycare.app.ui.components.ChoiceChip
import org.polycare.app.ui.components.GlassCard
import org.polycare.app.ui.components.Hairline
import org.polycare.app.ui.components.LabelledField
import org.polycare.app.ui.components.PrimaryButton
import org.polycare.app.ui.components.ScreenHeader
import org.polycare.app.ui.components.SecondaryButton
import org.polycare.app.ui.components.SectionLabel
import org.polycare.app.ui.components.StatusPill
import org.polycare.app.ui.components.ToggleRow
import org.polycare.app.ui.theme.Brand

private val Accent = Brand.Pink

/** What the ASHA has asked to do that needs an explicit "yes" first. */
private data class Confirm(val title: String, val message: String, val action: String, val run: () -> Unit)

/**
 * Household and member records with consent capture, editing, a visit log, and the ways out: a
 * family can withdraw consent (everything recorded about them is erased) or be deleted outright.
 * A member can only be added while the household's consent stands; there is no way to add one
 * without it, matching invariant 7 in the UI, not just in the repository.
 */
@Composable
fun HouseholdsScreen(contentPadding: PaddingValues, onBack: () -> Unit, viewModel: HouseholdsViewModel = hiltViewModel()) {
    val households by viewModel.households.collectAsStateWithLifecycle()
    val members by viewModel.members.collectAsStateWithLifecycle()
    val storageWarning by viewModel.storageWarning.collectAsStateWithLifecycle()
    var expandedId by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(c.title) },
            text = { Text(c.message) },
            confirmButton = { TextButton(onClick = { c.run(); confirm = null }) { Text(c.action, color = Brand.RedInk) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }

    LazyColumn(
        modifier = Modifier.statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader("Households", Accent, onBack = onBack)
        }
        item {
            Spacer(Modifier.height(4.dp))
            Text("Families & visits", style = MaterialTheme.typography.displaySmall, color = Brand.Ink)
            Spacer(Modifier.height(4.dp))
            Text(
                "Household records are encrypted and stored on this phone. They are not synced.",
                style = MaterialTheme.typography.labelSmall,
                color = Brand.InkMuted,
            )
        }

        storageWarning?.let { warning ->
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text(warning, style = MaterialTheme.typography.bodyMedium, color = Brand.RedInk)
                    Spacer(Modifier.height(10.dp))
                    SecondaryButton(
                        "Rebuild from the change log",
                        onClick = {
                            confirm = Confirm(
                                "Rebuild household records?",
                                "This replaces the unreadable saved records with what the change log can restore. " +
                                    "Records created before the change log existed cannot be restored.",
                                "Rebuild",
                            ) {
                                val n = viewModel.rebuildFromChangeLog()
                                notice = "Rebuilt $n household${if (n == 1) "" else "s"} from the change log."
                            }
                        },
                        accent = Brand.Red,
                    )
                }
            }
        }
        notice?.let { text ->
            item {
                Text(text, style = MaterialTheme.typography.bodyMedium, color = Brand.Positive, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }

        item { AddHouseholdCard(onAdd = { head, village, consent -> viewModel.addHousehold(head, village, consent) }) }

        if (households.isEmpty()) {
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("No households registered yet.", style = MaterialTheme.typography.bodyMedium, color = Brand.InkMuted)
                }
            }
        }

        items(households, key = { it.id }) { household ->
            HouseholdCard(
                household = household,
                members = members.filter { it.householdId == household.id },
                expanded = expandedId == household.id,
                onToggle = { expandedId = if (expandedId == household.id) null else household.id },
                viewModel = viewModel,
                ask = { confirm = it },
            )
        }
    }
}

@Composable
private fun AddHouseholdCard(onAdd: (String, String, Boolean) -> Unit) {
    var head by remember { mutableStateOf("") }
    var village by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }

    GlassCard(Modifier.fillMaxWidth(), accent = Accent) {
        SectionLabel("Register a household", color = Brand.Ink)
        Spacer(Modifier.height(12.dp))
        LabelledField("Head of household", head) { head = it }
        Spacer(Modifier.height(10.dp))
        LabelledField("Village / area", village) { village = it }
        Spacer(Modifier.height(12.dp))
        ToggleRow(
            "This household has given consent for their information to be recorded",
            consent, { consent = it }, accent = Accent,
        )
        Spacer(Modifier.height(8.dp))
        val canAdd = head.isNotBlank() && village.isNotBlank() && consent
        PrimaryButton(
            "Add household",
            onClick = {
                onAdd(head, village, consent)
                head = ""; village = ""; consent = false
            },
            enabled = canAdd, accent = Accent,
        )
        if (head.isNotBlank() && village.isNotBlank() && !consent) {
            Spacer(Modifier.height(8.dp))
            Text("Consent is required before a household can be registered.", style = MaterialTheme.typography.labelSmall, color = Brand.RedInk)
        }
    }
}

@Composable
private fun HouseholdCard(
    household: Household,
    members: List<Member>,
    expanded: Boolean,
    onToggle: () -> Unit,
    viewModel: HouseholdsViewModel,
    ask: (Confirm) -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClickLabel = if (expanded) "Collapse household" else "Open household", role = Role.Button, onClick = onToggle),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(household.headOfHousehold, style = MaterialTheme.typography.titleMedium, color = Brand.Ink)
                Text(household.village, style = MaterialTheme.typography.bodySmall, color = Brand.InkMuted)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!household.consentGiven) StatusPill("No consent", dot = Brand.Red) else StatusPill("${members.size} member${if (members.size == 1) "" else "s"}", dot = Accent)
                Spacer(Modifier.width(8.dp))
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null, tint = Brand.InkMuted)
            }
        }
        if (!expanded) return@GlassCard

        Spacer(Modifier.height(12.dp))
        Hairline()
        Spacer(Modifier.height(8.dp))

        members.forEach { member -> MemberRow(member, viewModel, ask) }
        if (members.isEmpty() && household.consentGiven) {
            Text("No members yet.", style = MaterialTheme.typography.bodySmall, color = Brand.InkMuted, modifier = Modifier.padding(vertical = 8.dp))
        }

        if (household.consentGiven) {
            Spacer(Modifier.height(12.dp))
            SectionLabel("Add a member", color = Brand.Ink)
            Spacer(Modifier.height(8.dp))
            AddMemberRow(onAdd = { name, age, relation -> viewModel.addMember(household.id, name, age, relation) })

            Spacer(Modifier.height(20.dp))
            SectionLabel("Log a visit", color = Brand.Ink)
            Spacer(Modifier.height(8.dp))
            LogVisitForm(household, members, viewModel, onSaved = onToggle)
        } else {
            Spacer(Modifier.height(8.dp))
            Text(
                "Consent was withdrawn. Nothing is stored about this family and no member or visit can be added.",
                style = MaterialTheme.typography.bodyMedium, color = Brand.InkMuted,
            )
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("Household details", color = Brand.Ink)
        Spacer(Modifier.height(8.dp))
        EditHouseholdForm(household, viewModel, onSaved = onToggle)

        Spacer(Modifier.height(20.dp))
        Hairline()
        Spacer(Modifier.height(12.dp))
        if (household.consentGiven) {
            SecondaryButton(
                "Family withdrew consent",
                onClick = {
                    ask(
                        Confirm(
                            "Erase everything about ${household.headOfHousehold}?",
                            "Members, visits and follow-ups for this family will be erased from this phone. The household stays listed as \"No consent\". This cannot be undone.",
                            "Erase",
                        ) { viewModel.withdrawConsent(household.id) },
                    )
                },
                accent = Brand.Red,
            )
            Spacer(Modifier.height(8.dp))
        }
        SecondaryButton(
            "Delete household",
            onClick = {
                ask(
                    Confirm(
                        "Delete this household?",
                        "The household and all its members, visits and follow-ups will be removed from this phone. This cannot be undone.",
                        "Delete",
                    ) { viewModel.deleteHousehold(household.id) },
                )
            },
            icon = Icons.Outlined.Delete, accent = Brand.Red,
        )
    }
}

@Composable
private fun MemberRow(member: Member, viewModel: HouseholdsViewModel, ask: (Confirm) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    if (editing) {
        var name by remember(member.id) { mutableStateOf(member.name) }
        var age by remember(member.id) { mutableStateOf(member.age.toString()) }
        var relation by remember(member.id) { mutableStateOf(member.relation) }
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            LabelledField("Member name", name) { name = it }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LabelledField("Age", age, modifier = Modifier.weight(1f), keyboardType = KeyboardType.Number) { age = it.filter(Char::isDigit) }
                LabelledField("Relation", relation, modifier = Modifier.weight(1f)) { relation = it }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Cancel", { editing = false }, Modifier.weight(1f), accent = Accent)
                PrimaryButton(
                    "Save",
                    { if (viewModel.updateMember(member.id, name, age.toIntOrNull() ?: 0, relation)) editing = false },
                    Modifier.weight(1f), enabled = name.isNotBlank(), accent = Accent,
                )
            }
        }
    } else {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${member.name} · ${member.relation}", style = MaterialTheme.typography.bodyMedium, color = Brand.Ink)
                Text("${member.age} years", style = MaterialTheme.typography.bodySmall, color = Brand.InkMuted)
            }
            AppIconButton(Icons.Outlined.Edit, "Edit ${member.name}", { editing = true }, tint = Brand.Ink, container = Accent.copy(alpha = 0.18f))
            Spacer(Modifier.width(8.dp))
            AppIconButton(
                Icons.Outlined.Delete, "Delete ${member.name}",
                {
                    ask(
                        Confirm(
                            "Delete ${member.name}?",
                            "Their visits and follow-ups will be removed from this phone too. This cannot be undone.",
                            "Delete",
                        ) { viewModel.deleteMember(member.id) },
                    )
                },
                tint = Brand.Red, container = Brand.Red.copy(alpha = 0.12f),
            )
        }
    }
}

@Composable
private fun AddMemberRow(onAdd: (String, Int, String) -> Boolean) {
    var name by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    var relation by remember { mutableStateOf("") }

    Column {
        LabelledField("Member name", name) { name = it }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LabelledField("Age", age, modifier = Modifier.weight(1f), keyboardType = KeyboardType.Number) { age = it.filter(Char::isDigit) }
            LabelledField("Relation", relation, modifier = Modifier.weight(1f)) { relation = it }
        }
        Spacer(Modifier.height(8.dp))
        SecondaryButton(
            "Add member",
            onClick = {
                if (onAdd(name, age.toIntOrNull() ?: 0, relation.ifBlank { "Member" })) {
                    name = ""; age = ""; relation = ""
                }
            },
            icon = Icons.Outlined.PersonAdd, enabled = name.isNotBlank(), accent = Accent,
        )
    }
}

@Composable
private fun EditHouseholdForm(household: Household, viewModel: HouseholdsViewModel, onSaved: () -> Unit) {
    var head by remember(household.id, household.headOfHousehold) { mutableStateOf(household.headOfHousehold) }
    var village by remember(household.id, household.village) { mutableStateOf(household.village) }
    var saved by remember { mutableStateOf(false) }
    LabelledField("Head of household", head) { head = it; saved = false }
    Spacer(Modifier.height(8.dp))
    LabelledField("Village / area", village) { village = it; saved = false }
    Spacer(Modifier.height(8.dp))
    val changed = head.trim() != household.headOfHousehold || village.trim() != household.village
    SecondaryButton(
        if (saved) "Saved" else "Save changes",
        onClick = {
            saved = viewModel.updateHousehold(household.id, head, village)
            if (saved) onSaved()
        },
        enabled = changed && head.isNotBlank() && village.isNotBlank(), icon = Icons.Outlined.Edit, accent = Accent,
    )
}

private val FollowUps = listOf<Pair<String, Int?>>("None" to null, "Tomorrow" to 1, "3 days" to 3, "1 week" to 7, "2 weeks" to 14, "1 month" to 30)

/** Logs a visit that was not on the due list; can schedule the next one. */
@Composable
private fun LogVisitForm(household: Household, members: List<Member>, viewModel: HouseholdsViewModel, onSaved: () -> Unit) {
    var memberId by remember(household.id) { mutableStateOf<String?>(null) }
    var type by remember { mutableStateOf(VisitType.ROUTINE) }
    var notes by remember { mutableStateOf("") }
    var highRisk by remember { mutableStateOf(false) }
    var followUp by remember { mutableStateOf<Int?>(null) }
    var saved by remember { mutableStateOf(false) }

    Text("Who was visited", style = MaterialTheme.typography.labelLarge, color = Brand.InkMuted)
    Spacer(Modifier.height(6.dp))
    ChipRow {
        ChoiceChip("Whole household", selected = memberId == null, onClick = { memberId = null; saved = false }, accent = Accent)
        members.forEach { m -> ChoiceChip(m.name, selected = memberId == m.id, onClick = { memberId = m.id; saved = false }, accent = Accent) }
    }
    Spacer(Modifier.height(12.dp))
    Text("Type of visit", style = MaterialTheme.typography.labelLarge, color = Brand.InkMuted)
    Spacer(Modifier.height(6.dp))
    ChipRow { VisitType.entries.forEach { t -> ChoiceChip(t.label, selected = type == t, onClick = { type = t; saved = false }, accent = Accent) } }
    Spacer(Modifier.height(12.dp))
    LabelledField("Notes (symptoms, advice, vitals)", notes, singleLine = false) { notes = it; saved = false }
    ToggleRow("Danger sign observed (mark high risk)", highRisk, { highRisk = it; saved = false }, accent = Brand.Red)
    Spacer(Modifier.height(4.dp))
    Text("Next visit", style = MaterialTheme.typography.labelLarge, color = Brand.InkMuted)
    Spacer(Modifier.height(6.dp))
    ChipRow { FollowUps.forEach { (label, days) -> ChoiceChip(label, selected = followUp == days, onClick = { followUp = days; saved = false }, accent = Accent) } }
    Spacer(Modifier.height(12.dp))
    PrimaryButton(
        if (saved) "Visit saved" else "Save visit",
        onClick = {
            val member = members.firstOrNull { it.id == memberId }
            saved = viewModel.logVisit(household.id, member?.id, member?.name, type, notes, highRisk, followUp)
            if (saved) { notes = ""; highRisk = false; followUp = null; onSaved() }
        },
        accent = Accent,
    )
}
