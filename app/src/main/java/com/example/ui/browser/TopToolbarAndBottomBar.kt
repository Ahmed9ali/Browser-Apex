package com.example.ui.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.ApexMotion
import com.example.ui.theme.EmphasizedTypography
import com.example.ui.theme.LocalReducedMotion
import com.example.ui.theme.rememberMorphingCornerShape
import com.example.ui.viewmodel.BrowserTab

/**
 * Clean Material 3 TopAppBar without top Settings or Automation clutter:
 * - On Home screen: Displays the Apex Browser title & emblem.
 * - On Web pages: Displays the HTTPS address pill and Reload action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactTopToolbar(
    visible: Boolean,
    activeTab: BrowserTab,
    onAddressPillClick: () -> Unit,
    onReloadClick: () -> Unit,
    searchEngine: com.example.data.preferences.SearchEngineOption = com.example.data.preferences.SearchEngineOption.DUCKDUCKGO,
    modifier: Modifier = Modifier
) {
    val reducedMotion = LocalReducedMotion.current

    val enterTransition = if (reducedMotion) {
        fadeIn()
    } else {
        expandVertically(animationSpec = ApexMotion.spatialSpring()) +
            fadeIn(animationSpec = ApexMotion.effectsSpring())
    }
    val exitTransition = if (reducedMotion) {
        fadeOut()
    } else {
        shrinkVertically(animationSpec = ApexMotion.spatialSpring()) +
            fadeOut(animationSpec = ApexMotion.effectsSpring())
    }

    AnimatedVisibility(
        visible = visible,
        enter = enterTransition,
        exit = exitTransition,
        modifier = modifier
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                title = {
                    if (activeTab.isHomePage) {
                        val homeBarShape = RoundedCornerShape(16.dp)
                        Surface(
                            shape = homeBarShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            border = BorderStroke(
                                1.dp,
                                if (activeTab.isPrivate) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 12.dp)
                                .height(44.dp)
                                .clip(homeBarShape)
                                .clickable(onClick = onAddressPillClick)
                                .testTag("home_top_search_bar")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp)
                            ) {
                                SearchEngineLogo(
                                    engine = searchEngine,
                                    size = 22.dp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = stringResource(R.string.home_search_placeholder_clean),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (activeTab.isPrivate) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    IncognitoToolbarBadge()
                                }
                            }
                        }
                    } else {
                        val addressBarShape = RoundedCornerShape(16.dp)
                        Surface(
                            shape = addressBarShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            border = BorderStroke(
                                1.dp,
                                if (activeTab.isPrivate) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 12.dp)
                                .height(44.dp)
                                .clip(addressBarShape)
                                .clickable(onClick = onAddressPillClick)
                                .testTag("address_bar_container")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Lock,
                                    contentDescription = stringResource(R.string.cd_https_secure),
                                    tint = if (activeTab.wasUpgradedToHttps) {
                                        MaterialTheme.colorScheme.tertiary
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                    modifier = Modifier.size(16.dp)
                                )

                                Spacer(modifier = Modifier.width(8.dp))

                                Text(
                                    text = activeTab.url,
                                    style = EmphasizedTypography.addressBarActive,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (activeTab.isPrivate) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    IncognitoToolbarBadge()
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.testTag("material_top_app_bar")
            )

            if (activeTab.isLoading && activeTab.progress < 1f) {
                val animatedProgress = androidx.compose.animation.core.animateFloatAsState(
                    targetValue = activeTab.progress.coerceIn(0.04f, 1f),
                    label = "WebPageLoadingProgress"
                )
                LinearProgressIndicator(
                    progress = { animatedProgress.value },
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .testTag("page_loading_indicator")
                )
            }
        }
    }
}

@Composable
private fun IncognitoToolbarBadge() {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary),
        modifier = Modifier.testTag("incognito_mode_badge")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            IncognitoGlassesIcon(
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = stringResource(R.string.incognito_badge_label),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

/**
 * Ergonomic Bottom NavigationBar featuring:
 * Home • Bookmarks • Search • Tabs • Hamburger Menu (opens the Browser Menu Bottom Sheet with Settings).
 */
@Composable
fun BraveBottomNavigationBar(
    isHomeActive: Boolean,
    isBookmarksScreenOpen: Boolean,
    isSettingsScreenOpen: Boolean,
    isMenuSheetOpen: Boolean,
    isCurrentWebPageBookmarked: Boolean,
    isTabSwitcherOpen: Boolean,
    isSearchScreenOpen: Boolean,
    openTabCount: Int,
    onHomeClick: () -> Unit,
    onBookmarksClick: () -> Unit,
    onSearchClick: () -> Unit,
    onTabsClick: () -> Unit,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        modifier = modifier
            .fillMaxWidth()
            .testTag("bottom_navigation_bar")
    ) {
        // 1. Home
        val homeSelected = isHomeActive &&
            !isBookmarksScreenOpen &&
            !isSettingsScreenOpen &&
            !isTabSwitcherOpen &&
            !isSearchScreenOpen &&
            !isMenuSheetOpen

        NavigationBarItem(
            selected = homeSelected,
            onClick = onHomeClick,
            icon = {
                Icon(
                    imageVector = if (homeSelected) Icons.Filled.Home else Icons.Outlined.Home,
                    contentDescription = stringResource(R.string.nav_home)
                )
            },
            label = { Text(stringResource(R.string.nav_home), maxLines = 1) },
            modifier = Modifier.testTag("nav_home_button")
        )

        // 2. Bookmarks
        NavigationBarItem(
            selected = isBookmarksScreenOpen ||
                (!isHomeActive && isCurrentWebPageBookmarked && !isSearchScreenOpen && !isSettingsScreenOpen),
            onClick = onBookmarksClick,
            icon = {
                val bookmarkIcon = when {
                    isBookmarksScreenOpen -> Icons.Filled.Bookmarks
                    isHomeActive || isSettingsScreenOpen -> Icons.Outlined.Bookmarks
                    isCurrentWebPageBookmarked -> Icons.Filled.Bookmark
                    else -> Icons.Outlined.BookmarkAdd
                }
                Icon(
                    imageVector = bookmarkIcon,
                    contentDescription = stringResource(R.string.nav_bookmarks)
                )
            },
            label = { Text(stringResource(R.string.nav_bookmarks), maxLines = 1) },
            modifier = Modifier.testTag("nav_bookmarks_button")
        )

        // 3. Search
        NavigationBarItem(
            selected = isSearchScreenOpen,
            onClick = onSearchClick,
            icon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = stringResource(R.string.nav_search)
                )
            },
            label = { Text(stringResource(R.string.nav_search), maxLines = 1) },
            modifier = Modifier.testTag("nav_search_button")
        )

        // 4. Tabs with square count icon (Brave / Chrome style)
        NavigationBarItem(
            selected = isTabSwitcherOpen,
            onClick = onTabsClick,
            icon = {
                SquareTabCounterIcon(
                    count = openTabCount,
                    isSelected = isTabSwitcherOpen
                )
            },
            label = { Text(stringResource(R.string.nav_tabs), maxLines = 1) },
            modifier = Modifier.testTag("nav_tabs_button")
        )

        // 5. Hamburger Menu (opens Browser Menu ModalBottomSheet containing Settings)
        NavigationBarItem(
            selected = isMenuSheetOpen || isSettingsScreenOpen,
            onClick = onMenuClick,
            icon = {
                Icon(
                    imageVector = Icons.Filled.Menu,
                    contentDescription = stringResource(R.string.cd_open_menu)
                )
            },
            label = { Text(stringResource(R.string.nav_menu), maxLines = 1) },
            modifier = Modifier.testTag("nav_menu_button")
        )
    }
}

/**
 * Brave & Chrome style square tab counter icon with rounded corners, stroke border,
 * and the open tabs count rendered inside the square.
 */
@Composable
fun SquareTabCounterIcon(
    count: Int,
    isSelected: Boolean = false,
    modifier: Modifier = Modifier
) {
    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val displayText = when {
        count > 99 -> ":D"
        count <= 0 -> "1"
        else -> count.toString()
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f)
        },
        border = BorderStroke(2.dp, borderColor),
        modifier = modifier
            .size(22.dp)
            .testTag("square_tab_counter_icon")
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = displayText,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = if (displayText.length >= 2) 10.sp else 12.sp,
                    lineHeight = 12.sp,
                    textAlign = TextAlign.Center
                ),
                color = borderColor
            )
        }
    }
}

/**
 * Material 3 ModalBottomSheet opened from the bottom Hamburger Menu button.
 * Uses Material 3 Expressive Grouped Lists and places Settings cleanly inside the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserMenuBottomSheet(
    isHomeActive: Boolean,
    desktopViewportEnabled: Boolean,
    savedOfflinePagesCount: Int = 0,
    browsingHistoryCount: Int = 0,
    downloadsCount: Int = 0,
    onOpenSettings: () -> Unit,
    onNewTabClick: () -> Unit,
    onNewIncognitoTabClick: () -> Unit = {},
    onOpenBookmarksClick: () -> Unit,
    onOpenHistoryClick: () -> Unit = {},
    onOpenDownloadsClick: () -> Unit = {},
    onSaveOfflineClick: () -> Unit = {},
    onOpenOfflinePagesClick: () -> Unit = {},
    onReloadPageClick: () -> Unit,
    onToggleDesktopMode: (Boolean) -> Unit,
    onClearBrowsingData: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = Modifier.testTag("hamburger_menu_bottom_sheet")
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
        ) {
            // Group 1: Quick Browser Actions (Material 3 Grouped List)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MaterialGroupSectionHeader(
                    title = stringResource(R.string.menu_quick_actions_section)
                )

                val quickActionsCount = if (isHomeActive) 5 else 6
                Column(
                    verticalArrangement = Arrangement.spacedBy(GroupedListSpacing),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    MaterialGroupListItem(
                        index = 0,
                        totalCount = quickActionsCount,
                        title = stringResource(R.string.menu_new_incognito_tab),
                        subtitle = stringResource(R.string.menu_new_incognito_subtitle),
                        role = Role.Button,
                        onClick = {
                            onDismiss()
                            onNewIncognitoTabClick()
                        },
                        leadingContent = {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    IncognitoGlassesIcon(
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        },
                        trailingContent = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        modifier = Modifier.testTag("menu_sheet_new_incognito_tab")
                    )

                    MenuSheetActionRow(
                        index = 1,
                        totalCount = quickActionsCount,
                        icon = Icons.Outlined.History,
                        title = stringResource(R.string.menu_history),
                        subtitle = if (browsingHistoryCount > 0) {
                            "$browsingHistoryCount ${if (browsingHistoryCount == 1) "page in timeline" else "pages in timeline"}"
                        } else {
                            stringResource(R.string.menu_history_subtitle)
                        },
                        onClick = {
                            onDismiss()
                            onOpenHistoryClick()
                        },
                        tag = "menu_sheet_history"
                    )

                    MenuSheetActionRow(
                        index = 2,
                        totalCount = quickActionsCount,
                        icon = Icons.Outlined.Download,
                        title = stringResource(R.string.menu_downloads),
                        subtitle = if (downloadsCount > 0) {
                            "$downloadsCount ${if (downloadsCount == 1) "file in downloads" else "files in downloads"} • 4× Turbo"
                        } else {
                            stringResource(R.string.menu_downloads_subtitle)
                        },
                        onClick = {
                            onDismiss()
                            onOpenDownloadsClick()
                        },
                        tag = "menu_sheet_downloads"
                    )

                    MenuSheetActionRow(
                        index = 3,
                        totalCount = quickActionsCount,
                        icon = Icons.Outlined.CloudDone,
                        title = stringResource(R.string.menu_offline_pages),
                        subtitle = if (savedOfflinePagesCount > 0) {
                            "$savedOfflinePagesCount ${if (savedOfflinePagesCount == 1) "page saved" else "pages saved"}"
                        } else {
                            stringResource(R.string.menu_offline_pages_subtitle)
                        },
                        onClick = {
                            onDismiss()
                            onOpenOfflinePagesClick()
                        },
                        tag = "menu_sheet_offline_pages"
                    )

                    if (!isHomeActive) {
                        MenuSheetActionRow(
                            index = 4,
                            totalCount = quickActionsCount,
                            icon = Icons.Outlined.CloudDownload,
                            title = stringResource(R.string.menu_save_offline),
                            subtitle = stringResource(R.string.menu_save_offline_subtitle),
                            onClick = {
                                onDismiss()
                                onSaveOfflineClick()
                            },
                            tag = "menu_sheet_save_offline"
                        )
                    }

                    val desktopIndex = if (isHomeActive) 4 else 5
                    MaterialGroupListItem(
                        index = desktopIndex,
                        totalCount = quickActionsCount,
                        title = stringResource(R.string.menu_desktop_mode),
                        subtitle = stringResource(R.string.menu_desktop_subtitle),
                        role = Role.Switch,
                        onClick = { onToggleDesktopMode(!desktopViewportEnabled) },
                        leadingContent = {
                            MenuSheetIconBadge(
                                icon = Icons.Outlined.DesktopWindows,
                                highlighted = desktopViewportEnabled
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = desktopViewportEnabled,
                                onCheckedChange = null
                            )
                        },
                        modifier = Modifier.testTag("menu_sheet_desktop_switch")
                    )
                }
            }

            // Group 2: Preferences & Privacy (Settings + Clear Browsing Data)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MaterialGroupSectionHeader(
                    title = stringResource(R.string.menu_preferences_section)
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(GroupedListSpacing),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    MaterialGroupListItem(
                        index = 0,
                        totalCount = 2,
                        title = stringResource(R.string.menu_settings),
                        subtitle = stringResource(R.string.menu_settings_subtitle),
                        role = Role.Button,
                        onClick = {
                            onDismiss()
                            onOpenSettings()
                        },
                        leadingContent = {
                            MenuSheetIconBadge(
                                icon = Icons.Outlined.Settings,
                                highlighted = true
                            )
                        },
                        trailingContent = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        modifier = Modifier.testTag("menu_sheet_open_settings")
                    )

                    MaterialGroupListItem(
                        index = 1,
                        totalCount = 2,
                        title = stringResource(R.string.menu_clear_data),
                        subtitle = stringResource(R.string.menu_clear_data_subtitle),
                        role = Role.Button,
                        onClick = {
                            onDismiss()
                            onClearBrowsingData()
                        },
                        leadingContent = {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Outlined.CleaningServices,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        },
                        modifier = Modifier.testTag("menu_sheet_clear_data")
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuSheetActionRow(
    index: Int,
    totalCount: Int,
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    tag: String
) {
    MaterialGroupListItem(
        index = index,
        totalCount = totalCount,
        title = title,
        subtitle = subtitle,
        role = Role.Button,
        onClick = onClick,
        leadingContent = {
            MenuSheetIconBadge(icon = icon)
        },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        modifier = Modifier.testTag(tag)
    )
}

@Composable
private fun MenuSheetIconBadge(
    icon: ImageVector,
    highlighted: Boolean = false
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (highlighted) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        modifier = Modifier.size(40.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (highlighted) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
