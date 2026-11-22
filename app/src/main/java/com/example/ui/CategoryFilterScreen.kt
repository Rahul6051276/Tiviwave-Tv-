package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.models.ContentCategory
import com.example.models.ContentType
import com.example.models.PlaylistSource
import com.example.services.CountryCategoryClassifier
import com.example.ui.theme.BroadcastCyan
import com.example.ui.theme.BroadcastCyanDeep
import com.example.ui.theme.ConnectedEmerald
import com.example.ui.theme.DividerSlate
import com.example.ui.theme.ElevatedSlate
import com.example.ui.theme.HighlightSlate
import com.example.ui.theme.MidnightPanel
import com.example.ui.theme.ObsidianBase
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.OnCyanDark
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

/**
 * 2-Step Country & Category Pre-Load Filter Screen.
 * Step 1: Displays fetched categories split across Live TV, Movies (VOD), and Web Series.
 * Step 2: Groups categories by auto-detected Country/Region; user ticks desired categories
 *         and imports strictly those checked categories (excluding unchecked ones completely).
 */
@Composable
fun CategoryFilterScreen(
    source: PlaylistSource,
    categories: List<ContentCategory>,
    onToggleCategory: (String) -> Unit,
    onSelectCountry: (ContentType, String, Boolean) -> Unit,
    onSelectTabAll: (ContentType, Boolean) -> Unit,
    onImportSelected: () -> Unit,
    onCancel: () -> Unit
) {
    BackHandler { onCancel() }

    val availableTypes = remember(categories) {
        ContentType.entries.filter { type -> categories.any { it.type == type } }
            .ifEmpty { ContentType.entries }
    }
    var activeTab by remember(source.id) { mutableStateOf(availableTypes.first()) }

    val categoriesForTab = remember(categories, activeTab) {
        categories.filter { it.type == activeTab }
    }

    val countryGroups = remember(categoriesForTab) {
        val grouped = categoriesForTab.groupBy { it.countryGroup }
        val knownOrder = CountryCategoryClassifier.orderedGroups
        grouped.keys.sortedWith(
            compareBy<String> { key ->
                val idx = knownOrder.indexOf(key)
                if (idx >= 0) idx else Int.MAX_VALUE
            }.thenBy { it }
        )
    }

    var selectedCountry by remember(activeTab, countryGroups) {
        mutableStateOf(countryGroups.firstOrNull().orEmpty())
    }
    LaunchedEffect(countryGroups) {
        if (selectedCountry !in countryGroups) {
            selectedCountry = countryGroups.firstOrNull().orEmpty()
        }
    }

    val subCategories = remember(categoriesForTab, selectedCountry) {
        categoriesForTab
            .filter { it.countryGroup == selectedCountry }
            .sortedBy { it.name.lowercase() }
    }

    val totalCheckedCount = remember(categories) { categories.count { it.isSelected } }
    val firstFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { firstFocusRequester.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid)
            .padding(horizontal = 28.dp, vertical = 20.dp)
    ) {
        // Header Row: Title + Source Badge + Import Action
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                TvActionButton(
                    text = "Back",
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    onClick = onCancel,
                    testTag = "filter_back_button"
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        text = "Country & Category Pre-Load Filter",
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${source.name} • ${source.type.displayName} • Unchecked categories will NOT be imported",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.width(16.dp))

            TvActionButton(
                text = "Import Selected ($totalCheckedCount)",
                icon = Icons.Default.Download,
                isPrimary = true,
                enabled = totalCheckedCount > 0,
                onClick = onImportSelected,
                testTag = "filter_import_selected_button"
            )
        }

        Spacer(Modifier.height(16.dp))

        // Top Content Type Tabs: Live TV | Movies (VOD) | Web Series
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ContentType.entries.forEachIndexed { index, type ->
                val countInTab = categories.count { it.type == type }
                val checkedInTab = categories.count { it.type == type && it.isSelected }
                val isCurrent = activeTab == type
                val tabIcon = when (type) {
                    ContentType.LIVE -> Icons.Default.LiveTv
                    ContentType.VOD -> Icons.Default.Movie
                    ContentType.SERIES -> Icons.Default.Tv
                }

                TvFocusableSurface(
                    onClick = { activeTab = type },
                    selected = isCurrent,
                    focusScale = 1.04f,
                    containerColor = MidnightPanel,
                    selectedContainerColor = BroadcastCyanDeep.copy(alpha = 0.45f),
                    focusedContainerColor = BroadcastCyan,
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                    modifier = Modifier
                        .then(if (index == 0) Modifier.focusRequester(firstFocusRequester) else Modifier)
                        .testTag("filter_tab_${type.name.lowercase()}")
                ) { isFocused ->
                    val tint = if (isFocused) OnCyanDark else if (isCurrent) TextPrimary else TextSecondary
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(tabIcon, contentDescription = type.label, tint = tint, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = type.label,
                            style = MaterialTheme.typography.titleMedium,
                            color = tint,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(10.dp))
                        TvBadge(
                            text = "$checkedInTab / $countInTab",
                            containerColor = if (isFocused) ObsidianVoid.copy(alpha = 0.25f) else ElevatedSlate,
                            contentColor = if (isFocused) OnCyanDark else if (checkedInTab > 0) BroadcastCyan else TextMuted
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            TvActionButton(
                text = "Select All ${activeTab.label}",
                icon = Icons.Default.DoneAll,
                onClick = { onSelectTabAll(activeTab, true) },
                testTag = "filter_select_all_tab_button"
            )
            TvActionButton(
                text = "Clear Tab",
                icon = Icons.Default.ClearAll,
                onClick = { onSelectTabAll(activeTab, false) },
                testTag = "filter_clear_tab_button"
            )
        }

        Spacer(Modifier.height(16.dp))

        // Main 2-Pane TV Selector: Left = Country/Region Groups, Right = Sub-Categories Checklist
        if (categoriesForTab.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(ObsidianBase)
                    .border(1.dp, DividerSlate, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No ${activeTab.label} categories reported by this playlist source.",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextMuted
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                // Left Pane: Countries / Regions
                Column(
                    modifier = Modifier
                        .weight(0.34f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(14.dp))
                        .background(ObsidianBase)
                        .border(1.dp, DividerSlate.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Public, contentDescription = "Countries", tint = BroadcastCyan, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Step 1 • Countries & Regions",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                    }
                    Spacer(Modifier.height(10.dp))

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(4.dp)
                    ) {
                        items(countryGroups, key = { it }) { country ->
                            val catsInCountry = categoriesForTab.filter { it.countryGroup == country }
                            val checkedInCountry = catsInCountry.count { it.isSelected }
                            val isSelected = selectedCountry == country

                            TvFocusableSurface(
                                onClick = { selectedCountry = country },
                                onFocused = { selectedCountry = country },
                                selected = isSelected,
                                focusScale = 1.03f,
                                containerColor = MidnightPanel,
                                selectedContainerColor = HighlightSlate,
                                focusedContainerColor = BroadcastCyanDeep,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("filter_country_${country.lowercase().replace(" ", "_")}")
                            ) { isFocused ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = country,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = if (isFocused || isSelected) TextPrimary else TextSecondary,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (checkedInCountry > 0) {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = "Selected categories",
                                                tint = ConnectedEmerald,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                        }
                                        Text(
                                            text = "$checkedInCountry/${catsInCountry.size}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (checkedInCountry > 0) BroadcastCyan else TextMuted
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Right Pane: Sub-Categories inside Selected Country
                Column(
                    modifier = Modifier
                        .weight(0.66f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(14.dp))
                        .background(ObsidianBase)
                        .border(1.dp, DividerSlate.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Step 2 • $selectedCountry Sub-Categories",
                                style = MaterialTheme.typography.titleLarge,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${subCategories.count { it.isSelected }} of ${subCategories.size} checked for import",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TvActionButton(
                                text = "Select All",
                                icon = Icons.Default.DoneAll,
                                onClick = { onSelectCountry(activeTab, selectedCountry, true) },
                                testTag = "filter_select_all_country_button"
                            )
                            TvActionButton(
                                text = "Clear",
                                icon = Icons.Default.ClearAll,
                                onClick = { onSelectCountry(activeTab, selectedCountry, false) },
                                testTag = "filter_clear_country_button"
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(4.dp)
                    ) {
                        items(subCategories, key = { it.id }) { cat ->
                            TvCheckboxRow(
                                title = cat.name,
                                subtitle = "${cat.countryGroup} • ${cat.type.label}",
                                checked = cat.isSelected,
                                badgeText = if (cat.itemCount > 0) "${cat.itemCount} items" else null,
                                onToggle = { onToggleCategory(cat.id) },
                                testTag = "filter_category_${cat.id}"
                            )
                        }
                    }
                }
            }
        }
    }
}
