package com.example.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.WebView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.automation.ExtractedRecord
import com.example.automation.NanoChatMessage
import com.example.automation.NanoMultiAgentEngine
import com.example.automation.RetrieverEngine
import com.example.automation.TabSnapshotTarget
import com.example.data.local.ApexDatabase
import com.example.data.local.BlockedDomainLogEntity
import com.example.data.local.BookmarkEntity
import com.example.data.local.BrowsingHistoryEntity
import com.example.data.local.DownloadItemEntity
import com.example.data.local.ExtractionRuleEntity
import com.example.data.local.OfflinePageEntity
import com.example.data.local.PrivacyStatSummaryEntity
import com.example.data.local.SearchHistoryEntity
import com.example.data.local.TopVisitEntity
import com.example.data.preferences.BrowserPreferences
import com.example.data.preferences.SearchEngineOption
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.BrowserRepository
import com.example.engine.FastDownloadEngine
import com.example.engine.OfflineStorageUtil
import com.example.engine.PerformanceOptimizer
import com.example.engine.PreloadCandidate
import com.example.engine.PrivacyManager
import com.example.engine.SearchSuggestionEngine
import com.example.engine.SearchSuggestionItem
import com.example.ui.theme.SeedPalettePreset
import com.example.ui.theme.TabGroupColorOption
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TabGroupInfo(
    val id: String = "grp_${UUID.randomUUID()}",
    val title: String,
    val colorOption: TabGroupColorOption = TabGroupColorOption.COBALT
)

data class BrowserTab(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Home",
    val url: String = "apex://home",
    val requestedUrl: String = url,
    val loadRequestId: Long = 1L,
    val isPrivate: Boolean = false,
    val groupId: String? = null,
    val tabColor: TabGroupColorOption? = null,
    val isLoading: Boolean = false,
    val progress: Float = 0f,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val blockedCountOnPage: Int = 0,
    val isHttps: Boolean = true,
    val wasUpgradedToHttps: Boolean = false,
    val isOfflineCopy: Boolean = false,
    val offlineSavedAt: Long? = null,
    val offlineHtmlContent: String? = null,
    val previewThumbnail: Bitmap? = null,
    val lastActiveTimeMillis: Long = System.currentTimeMillis()
) {
    val isHomePage: Boolean
        get() = url.isBlank() || url.equals("apex://home", ignoreCase = true)
}

sealed interface TabGridEntry {
    val gridKey: String

    data class SingleTab(val tab: BrowserTab) : TabGridEntry {
        override val gridKey: String = "single_${tab.id}"
    }

    data class GroupTab(
        val group: TabGroupInfo,
        val tabs: List<BrowserTab>
    ) : TabGridEntry {
        override val gridKey: String = "group_${group.id}"
    }
}

enum class ExtensionModule {
    NANO,
    RETRIEVER
}

enum class RetrieverDisplayMode {
    TABLE,
    CARDS
}

data class BrowserUiState(
    // Fresh launch: strictly 1 clean Home tab — no pre-opened tabs
    val tabs: List<BrowserTab> = listOf(
        BrowserTab(id = "tab_home", title = "Home", url = "apex://home")
    ),
    val tabGroups: List<TabGroupInfo> = emptyList(),
    val expandedGroupId: String? = null,
    val editingGroupId: String? = null,
    val activeTabId: String = "tab_home",
    val previousTabIndex: Int = 0,
    val isToolbarVisible: Boolean = true,
    val isSearchScreenOpen: Boolean = false,
    val searchQuery: String = "",
    val searchSuggestions: List<SearchSuggestionItem> = emptyList(),
    val isLoadingSuggestions: Boolean = false,
    val isTabSwitcherOpen: Boolean = false,
    val isBookmarksScreenOpen: Boolean = false,
    val isHistoryScreenOpen: Boolean = false,
    val isDownloadsScreenOpen: Boolean = false,
    val isOfflinePagesScreenOpen: Boolean = false,
    val isSettingsScreenOpen: Boolean = false,
    val isMenuSheetOpen: Boolean = false,
    val isIncognitoUnlocked: Boolean = false,
    val isAutomationOpen: Boolean = false,
    val activeExtension: ExtensionModule = ExtensionModule.NANO,
    val snackbarNotice: String? = null,
    val preloadCandidates: List<PreloadCandidate> = emptyList(),
    val nanoMessages: List<NanoChatMessage> = listOf(
        NanoChatMessage(
            id = "welcome_nano",
            isUser = false,
            text = "Nano Multi-Agent ready. PlannerAgent, NavigatorAgent, and ValidatorAgent run locally over the active WebView DOM."
        )
    ),
    val isNanoRunning: Boolean = false,
    val selectedRuleId: Long? = null,
    val retrieverPromptInput: String = "",
    val extractedRecords: List<ExtractedRecord> = emptyList(),
    val isRetrieverRunning: Boolean = false,
    val retrieverStatusBanner: String = "Ready for single-page or multi-tab parallel capture",
    val retrieverDisplayMode: RetrieverDisplayMode = RetrieverDisplayMode.TABLE
) {
    val activeTab: BrowserTab
        get() = tabs.firstOrNull { it.id == activeTabId } ?: tabs.first()

    val activeTabIndex: Int
        get() = tabs.indexOfFirst { it.id == activeTabId }.coerceAtLeast(0)

    val activeTabGroup: TabGroupInfo?
        get() {
            val gId = activeTab.groupId ?: return null
            return tabGroups.firstOrNull { it.id == gId }
        }

    val activeGroupTabs: List<BrowserTab>
        get() {
            val gId = activeTabGroup?.id ?: return emptyList()
            return tabs.filter { it.groupId == gId }
        }

    val editingGroup: TabGroupInfo?
        get() {
            val editId = editingGroupId ?: return null
            return tabGroups.firstOrNull { it.id == editId }
        }

    val incognitoTabCount: Int
        get() = tabs.count { it.isPrivate }

    val gridEntries: List<TabGridEntry>
        get() {
            val emittedGroups = mutableSetOf<String>()
            val result = mutableListOf<TabGridEntry>()
            val groupLookup = tabGroups.associateBy { it.id }

            for (tab in tabs) {
                val gId = tab.groupId
                if (gId == null) {
                    result.add(TabGridEntry.SingleTab(tab))
                } else if (emittedGroups.add(gId)) {
                    val memberTabs = tabs.filter { it.groupId == gId }
                    val info = groupLookup[gId]
                    if (info != null && memberTabs.isNotEmpty()) {
                        result.add(TabGridEntry.GroupTab(group = info, tabs = memberTabs))
                    } else if (memberTabs.size >= 2) {
                        result.add(
                            TabGridEntry.GroupTab(
                                group = TabGroupInfo(id = gId, title = "Tab Group"),
                                tabs = memberTabs
                            )
                        )
                    } else {
                        result.add(TabGridEntry.SingleTab(tab))
                    }
                }
            }
            return result
        }
}

class BrowserViewModel(
    private val repository: BrowserRepository,
    val privacyManager: PrivacyManager = PrivacyManager(),
    val performanceOptimizer: PerformanceOptimizer = PerformanceOptimizer(),
    private val suggestionEngine: SearchSuggestionEngine = SearchSuggestionEngine(),
    private val nanoEngine: NanoMultiAgentEngine = NanoMultiAgentEngine(),
    private val retrieverEngine: RetrieverEngine = RetrieverEngine(),
    private val fastDownloadEngine: FastDownloadEngine? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    val preferencesState: StateFlow<BrowserPreferences> = repository.preferencesFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = BrowserPreferences()
        )

    val privacyStatsState: StateFlow<PrivacyStatSummaryEntity> = repository.privacyStatsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = PrivacyStatSummaryEntity()
        )

    val blockedLogsState: StateFlow<List<BlockedDomainLogEntity>> = repository.recentBlockedLogsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val extractionRulesState: StateFlow<List<ExtractionRuleEntity>> = repository.extractionRulesFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val bookmarksState: StateFlow<List<BookmarkEntity>> = repository.bookmarksFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val searchHistoryState: StateFlow<List<SearchHistoryEntity>> = repository.searchHistoryFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val offlinePagesState: StateFlow<List<OfflinePageEntity>> = repository.offlinePagesFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val offlinePagesCountState: StateFlow<Int> = repository.offlinePagesCountFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0
        )

    val topVisitsState: StateFlow<List<TopVisitEntity>> = repository.topVisitsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val browsingHistoryState: StateFlow<List<BrowsingHistoryEntity>> = repository.browsingHistoryFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val browsingHistoryCountState: StateFlow<Int> = repository.browsingHistoryCountFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0
        )

    val downloadsState: StateFlow<List<DownloadItemEntity>> = repository.downloadsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val downloadsCountState: StateFlow<Int> = repository.downloadsCountFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0
        )

    private data class RecordedTabVisit(
        val loadRequestId: Long,
        val canonicalUrl: String,
        val host: String,
        val recordedAtMillis: Long
    )

    private val webViewRegistry = mutableMapOf<String, WebView>()
    private val lastRecordedVisitByTab = mutableMapOf<String, RecordedTabVisit>()
    private var suggestionJob: Job? = null

    init {
        viewModelScope.launch {
            repository.ensureFreshInitialized()
        }
    }

    fun registerWebView(tabId: String, webView: WebView) {
        webViewRegistry[tabId] = webView
    }

    fun getWebView(tabId: String): WebView? = webViewRegistry[tabId]

    fun consumeSnackbarNotice() {
        _uiState.update { it.copy(snackbarNotice = null) }
    }

    // --- Dedicated Search Screen & Search-Engine Suggestions ---

    fun openSearchScreen(prefillCurrentUrl: Boolean = false) {
        _uiState.update {
            it.copy(
                isSearchScreenOpen = true,
                isBookmarksScreenOpen = false,
                isHistoryScreenOpen = false,
                isDownloadsScreenOpen = false,
                isSettingsScreenOpen = false,
                isMenuSheetOpen = false,
                isTabSwitcherOpen = false,
                isAutomationOpen = false,
                isToolbarVisible = true,
                searchQuery = "",
                searchSuggestions = emptyList(),
                isLoadingSuggestions = false
            )
        }
    }

    fun closeSearchScreen() {
        suggestionJob?.cancel()
        _uiState.update {
            it.copy(
                isSearchScreenOpen = false,
                isLoadingSuggestions = false
            )
        }
    }

    fun onSearchQueryChanged(newQuery: String) {
        _uiState.update {
            it.copy(
                searchQuery = newQuery,
                searchSuggestions = if (newQuery.isBlank()) emptyList() else it.searchSuggestions,
                isLoadingSuggestions = newQuery.isNotBlank()
            )
        }
        triggerSuggestionsFetch(newQuery, preferencesState.value.searchEngine)
    }

    fun selectSearchEngine(engine: SearchEngineOption) {
        viewModelScope.launch {
            repository.setSearchEngine(engine)
            val currentQ = _uiState.value.searchQuery
            if (currentQ.isNotBlank()) {
                triggerSuggestionsFetch(currentQ, engine)
            }
        }
    }

    private fun triggerSuggestionsFetch(query: String, engine: SearchEngineOption) {
        suggestionJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _uiState.update {
                it.copy(searchSuggestions = emptyList(), isLoadingSuggestions = false)
            }
            return
        }
        suggestionJob = viewModelScope.launch {
            delay(160)
            val results = suggestionEngine.fetchSuggestions(trimmed, engine)
            _uiState.update { state ->
                if (state.searchQuery.trim() == trimmed) {
                    state.copy(
                        searchSuggestions = results,
                        isLoadingSuggestions = false
                    )
                } else {
                    state
                }
            }
        }
    }

    fun deleteSearchHistoryItem(id: Long) {
        viewModelScope.launch { repository.deleteSearchHistoryItem(id) }
    }

    fun clearAllSearchHistory() {
        viewModelScope.launch { repository.clearAllSearchHistory() }
    }

    fun submitNavigation(rawInput: String, targetTabId: String = _uiState.value.activeTabId) {
        val trimmedInput = rawInput.trim()
        if (trimmedInput.isEmpty()) return

        val prefs = preferencesState.value
        val normalized = privacyManager.normalizeAndUpgradeUrl(
            rawInput = trimmedInput,
            httpsUpgradeEnabled = prefs.httpsUpgradeEnabled,
            searchEngineTemplate = prefs.searchEngineTemplate
        )

        val targetTab = _uiState.value.tabs.firstOrNull { it.id == targetTabId }
        if (!normalized.isBuiltInScheme && targetTab?.isPrivate != true) {
            viewModelScope.launch {
                repository.recordSearchQuery(
                    query = trimmedInput,
                    engineName = prefs.searchEngine.displayName
                )
            }
        }

        if (normalized.wasUpgradedToHttps) {
            viewModelScope.launch { repository.recordHttpsUpgrade() }
        }

        suggestionJob?.cancel()
        lastRecordedVisitByTab.remove(targetTabId)

        val isHomeDest = normalized.finalUrl.equals("apex://home", ignoreCase = true)
        _uiState.update { state ->
            val updatedTabs = state.tabs.map { tab ->
                if (tab.id == targetTabId) {
                    tab.copy(
                        url = normalized.finalUrl,
                        requestedUrl = normalized.finalUrl,
                        loadRequestId = tab.loadRequestId + 1L,
                        title = if (isHomeDest) {
                            "Home"
                        } else {
                            deriveShortTitle(normalized.finalUrl)
                        },
                        blockedCountOnPage = 0,
                        isHttps = normalized.finalUrl.startsWith("https://") || normalized.isBuiltInScheme,
                        wasUpgradedToHttps = normalized.wasUpgradedToHttps,
                        isLoading = !isHomeDest,
                        progress = if (isHomeDest) 0f else 0.06f,
                        isOfflineCopy = false,
                        offlineSavedAt = null,
                        offlineHtmlContent = null
                    )
                } else {
                    tab
                }
            }
            state.copy(
                tabs = updatedTabs,
                isSearchScreenOpen = false,
                isLoadingSuggestions = false,
                isBookmarksScreenOpen = false,
                isHistoryScreenOpen = false,
                isDownloadsScreenOpen = false,
                isOfflinePagesScreenOpen = false,
                isSettingsScreenOpen = false,
                isTabSwitcherOpen = false,
                isToolbarVisible = true
            )
        }
    }

    fun recordHttpsUpgradeStat() {
        viewModelScope.launch { repository.recordHttpsUpgrade() }
    }

    fun openLinkInNewTab(
        rawUrl: String,
        inGroup: Boolean = false,
        isPrivate: Boolean = false,
        switchToTab: Boolean = true
    ) {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return

        val prefs = preferencesState.value
        val normalized = privacyManager.normalizeAndUpgradeUrl(
            rawInput = trimmed,
            httpsUpgradeEnabled = prefs.httpsUpgradeEnabled,
            searchEngineTemplate = prefs.searchEngineTemplate
        )

        _uiState.update { state ->
            val active = state.activeTab
            var updatedGroups = state.tabGroups
            var targetGroupId: String? = null
            var chosenColor: TabGroupColorOption? = null

            if (inGroup) {
                if (active.groupId != null) {
                    targetGroupId = active.groupId
                    chosenColor = state.tabGroups.firstOrNull { it.id == targetGroupId }?.colorOption ?: active.tabColor
                } else {
                    val newGrpId = "grp_${UUID.randomUUID()}"
                    val grpNumber = state.tabGroups.size + 1
                    chosenColor = active.tabColor ?: TabGroupColorOption.entries[
                        state.tabGroups.size % TabGroupColorOption.entries.size
                    ]
                    val newGrp = TabGroupInfo(
                        id = newGrpId,
                        title = "Group $grpNumber",
                        colorOption = chosenColor
                    )
                    updatedGroups = state.tabGroups + newGrp
                    targetGroupId = newGrpId
                }
            }

            val newTab = BrowserTab(
                title = deriveShortTitle(normalized.finalUrl),
                url = normalized.finalUrl,
                requestedUrl = normalized.finalUrl,
                loadRequestId = 1L,
                isPrivate = isPrivate,
                groupId = targetGroupId,
                tabColor = chosenColor,
                isLoading = true,
                progress = 0.06f,
                isHttps = normalized.finalUrl.startsWith("https://") || normalized.isBuiltInScheme,
                wasUpgradedToHttps = normalized.wasUpgradedToHttps
            )

            val baseTabs = if (inGroup && active.groupId == null && targetGroupId != null) {
                state.tabs.map { tab ->
                    if (tab.id == active.id) {
                        tab.copy(groupId = targetGroupId, tabColor = tab.tabColor ?: chosenColor)
                    } else {
                        tab
                    }
                }
            } else {
                state.tabs
            }

            val noticeText = when {
                isPrivate -> "Opened in Private tab"
                inGroup -> "Opened in new tab in group"
                else -> "Opened in new tab"
            }

            state.copy(
                previousTabIndex = state.activeTabIndex,
                tabs = baseTabs + newTab,
                tabGroups = updatedGroups,
                activeTabId = if (switchToTab) newTab.id else state.activeTabId,
                isHistoryScreenOpen = if (switchToTab) false else state.isHistoryScreenOpen,
                isIncognitoUnlocked = state.isIncognitoUnlocked || isPrivate,
                isToolbarVisible = true,
                snackbarNotice = noticeText
            )
        }
    }

    fun addLinkToReadingList(title: String, url: String) {
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) return
        val cleanTitle = title.trim().ifEmpty { deriveShortTitle(cleanUrl) }
        viewModelScope.launch {
            repository.saveBookmark(url = cleanUrl, title = cleanTitle)
            _uiState.update {
                it.copy(snackbarNotice = "Added '$cleanTitle' to reading list")
            }
        }
    }

    fun onWebViewPageStarted(tabId: String, url: String) {
        if (url.startsWith("data:") || url.isBlank()) return
        _uiState.update { state ->
            state.copy(
                tabs = state.tabs.map { tab ->
                    if (tab.id == tabId) {
                        val effectiveUrl = mapBaseUrlBackToScheme(url, tab.url)
                        tab.copy(
                            url = effectiveUrl,
                            isLoading = true,
                            progress = tab.progress.coerceAtLeast(0.08f),
                            isHttps = effectiveUrl.startsWith("https://") || effectiveUrl.startsWith("apex://")
                        )
                    } else {
                        tab
                    }
                }
            )
        }
    }

    fun onWebViewProgress(tabId: String, progress: Int) {
        val wv = webViewRegistry[tabId]
        _uiState.update { state ->
            state.copy(
                tabs = state.tabs.map { tab ->
                    if (tab.id == tabId) {
                        val isDone = progress >= 100
                        val liveUrl = wv?.url?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                            ?.let { mapBaseUrlBackToScheme(it, tab.url) }
                            ?: tab.url
                        val liveTitle = wv?.title?.takeIf { it.isNotBlank() } ?: tab.title
                        tab.copy(
                            url = liveUrl,
                            title = if (isDone) liveTitle else tab.title,
                            progress = (progress / 100f).coerceIn(0f, 1f),
                            isLoading = !isDone,
                            canGoBack = wv?.canGoBack() ?: tab.canGoBack,
                            canGoForward = wv?.canGoForward() ?: tab.canGoForward
                        )
                    } else {
                        tab
                    }
                }
            )
        }
    }

    fun onWebViewPageFinished(
        tabId: String,
        title: String?,
        url: String?,
        canGoBack: Boolean,
        canGoForward: Boolean
    ) {
        val capturedThumb = captureTabThumbnail(tabId)
        var finishedUrl = ""
        _uiState.update { state ->
            state.copy(
                tabs = state.tabs.map { tab ->
                    if (tab.id == tabId) {
                        val effectiveUrl = if (!url.isNullOrBlank()) {
                            mapBaseUrlBackToScheme(url, tab.url)
                        } else {
                            tab.url
                        }
                        finishedUrl = effectiveUrl
                        tab.copy(
                            title = title?.takeIf { it.isNotBlank() } ?: deriveShortTitle(effectiveUrl),
                            url = effectiveUrl,
                            isLoading = false,
                            progress = 1f,
                            canGoBack = canGoBack,
                            canGoForward = canGoForward,
                            previewThumbnail = capturedThumb ?: tab.previewThumbnail,
                            lastActiveTimeMillis = System.currentTimeMillis()
                        )
                    } else {
                        tab
                    }
                }
            )
        }

        val finishedTab = _uiState.value.tabs.firstOrNull { it.id == tabId }
        val wv = webViewRegistry[tabId]
        if (wv != null && tabId == _uiState.value.activeTabId && finishedTab?.isPrivate != true) {
            performanceOptimizer.preloadLikelyNextPages(
                webView = wv,
                enabled = preferencesState.value.aggressivePreloadEnabled
            ) { candidates ->
                _uiState.update { it.copy(preloadCandidates = candidates) }
            }
        }

        // Record browsing history for non-private tabs (deduplicated across multiple WebView onPageFinished callbacks)
        if (finishedTab != null &&
            !finishedTab.isPrivate &&
            !finishedTab.isHomePage &&
            finishedUrl.isNotBlank() &&
            !finishedUrl.equals("about:blank", ignoreCase = true) &&
            !finishedUrl.startsWith("data:", ignoreCase = true)
        ) {
            val canonicalUrl = repository.normalizeHistoryUrl(finishedUrl)
            if (canonicalUrl.isNotBlank()) {
                val now = System.currentTimeMillis()
                val host = canonicalUrl.substringAfter("://", canonicalUrl)
                    .substringBefore('/')
                    .removePrefix("www.")
                    .lowercase()
                val prev = lastRecordedVisitByTab[tabId]

                val isSameNavigationVisit = prev != null && (
                    prev.canonicalUrl.equals(canonicalUrl, ignoreCase = true) ||
                        (prev.loadRequestId == finishedTab.loadRequestId &&
                            (now - prev.recordedAtMillis) < 12_000L) ||
                        ((now - prev.recordedAtMillis) < 4_500L)
                )

                val previousUrlInSameLoad = if (isSameNavigationVisit) prev?.canonicalUrl else null
                lastRecordedVisitByTab[tabId] = RecordedTabVisit(
                    loadRequestId = finishedTab.loadRequestId,
                    canonicalUrl = canonicalUrl,
                    host = host,
                    recordedAtMillis = if (isSameNavigationVisit && prev != null) prev.recordedAtMillis else now
                )

                val shouldIncrementVisit = !isSameNavigationVisit
                val pageTitle = finishedTab.title
                val pageIsHttps = finishedTab.isHttps
                val pageBlockedCount = finishedTab.blockedCountOnPage

                viewModelScope.launch {
                    repository.recordPageVisit(
                        url = canonicalUrl,
                        title = pageTitle,
                        isHttps = pageIsHttps,
                        trackersBlockedOnVisit = pageBlockedCount,
                        incrementVisitCount = shouldIncrementVisit,
                        previousUrlInSameLoad = previousUrlInSameLoad
                    )
                }
            }
        }
    }

    fun onSubresourceBlocked(
        tabId: String,
        pageHost: String,
        blockedDomain: String,
        category: String,
        bytesSaved: Long,
        timeSavedMs: Long
    ) {
        incrementTabBlockedCount(tabId, 1)
        viewModelScope.launch {
            repository.recordBlockedItems(
                pageHost = pageHost,
                trackersDelta = if (category == "Tracker") 1 else 0,
                adsDelta = if (category == "Ad Network") 1 else 0,
                bytesDelta = bytesSaved,
                timeMsDelta = timeSavedMs,
                sampleDomain = blockedDomain,
                sampleCategory = category
            )
        }
    }

    private fun incrementTabBlockedCount(tabId: String, delta: Int) {
        _uiState.update { state ->
            state.copy(
                tabs = state.tabs.map { tab ->
                    if (tab.id == tabId) {
                        tab.copy(blockedCountOnPage = tab.blockedCountOnPage + delta)
                    } else {
                        tab
                    }
                }
            )
        }
    }

    fun onWebViewScrollDelta(dy: Int) {
        if (_uiState.value.activeTab.isHomePage || _uiState.value.isSearchScreenOpen) {
            if (!_uiState.value.isToolbarVisible) {
                _uiState.update { it.copy(isToolbarVisible = true) }
            }
            return
        }
        if (dy > 16 && _uiState.value.isToolbarVisible) {
            _uiState.update { it.copy(isToolbarVisible = false) }
        } else if (dy < -12 && !_uiState.value.isToolbarVisible) {
            _uiState.update { it.copy(isToolbarVisible = true) }
        }
    }

    // --- Tab & Draggable Tab Group Management ---

    fun selectTab(tabId: String) {
        _uiState.update { state ->
            val prevIdx = state.activeTabIndex
            val targetTab = state.tabs.firstOrNull { it.id == tabId } ?: return@update state
            state.copy(
                previousTabIndex = prevIdx,
                activeTabId = tabId,
                expandedGroupId = null,
                isTabSwitcherOpen = false,
                isBookmarksScreenOpen = false,
                isHistoryScreenOpen = false,
                isDownloadsScreenOpen = false,
                isSearchScreenOpen = false,
                isToolbarVisible = true
            )
        }
    }

    fun createNewTab(
        initialUrl: String = "apex://home",
        groupId: String? = null,
        stayInSwitcher: Boolean = groupId != null
    ) {
        val inheritedColor = groupId?.let { gId ->
            _uiState.value.tabGroups.firstOrNull { it.id == gId }?.colorOption
        }
        val newTab = BrowserTab(
            title = if (initialUrl == "apex://home") "Home" else deriveShortTitle(initialUrl),
            url = initialUrl,
            groupId = groupId,
            tabColor = inheritedColor
        )
        _uiState.update { state ->
            state.copy(
                previousTabIndex = state.activeTabIndex,
                tabs = state.tabs + newTab,
                activeTabId = if (!stayInSwitcher) newTab.id else state.activeTabId,
                isTabSwitcherOpen = stayInSwitcher,
                isBookmarksScreenOpen = false,
                isHistoryScreenOpen = false,
                isDownloadsScreenOpen = false,
                isSettingsScreenOpen = false,
                isSearchScreenOpen = false,
                isToolbarVisible = true
            )
        }
        if (initialUrl != "apex://home") {
            submitNavigation(initialUrl, newTab.id)
        }
    }

    fun createNewIncognitoTab(
        initialUrl: String = "apex://home",
        stayInSwitcher: Boolean = false
    ) {
        val newTab = BrowserTab(
            title = if (initialUrl == "apex://home") "Incognito" else deriveShortTitle(initialUrl),
            url = initialUrl,
            isPrivate = true
        )
        _uiState.update { state ->
            state.copy(
                previousTabIndex = state.activeTabIndex,
                tabs = state.tabs + newTab,
                activeTabId = if (!stayInSwitcher) newTab.id else state.activeTabId,
                isTabSwitcherOpen = stayInSwitcher,
                isBookmarksScreenOpen = false,
                isHistoryScreenOpen = false,
                isDownloadsScreenOpen = false,
                isOfflinePagesScreenOpen = false,
                isSettingsScreenOpen = false,
                isMenuSheetOpen = false,
                isSearchScreenOpen = false,
                isIncognitoUnlocked = true,
                isToolbarVisible = true,
                snackbarNotice = "Incognito mode active • History, cookies & site data will not be saved"
            )
        }
        if (initialUrl != "apex://home") {
            submitNavigation(initialUrl, newTab.id)
        }
    }

    fun closeAllIncognitoTabs() {
        val currentState = _uiState.value
        val incognitoIds = currentState.tabs.filter { it.isPrivate }.map { it.id }.toSet()
        if (incognitoIds.isEmpty()) {
            _uiState.update { it.copy(isIncognitoUnlocked = false) }
            return
        }

        incognitoIds.forEach { id ->
            lastRecordedVisitByTab.remove(id)
            webViewRegistry.remove(id)?.let { wv ->
                performanceOptimizer.wipeIncognitoWebView(wv)
                wv.destroy()
            }
        }
        performanceOptimizer.wipeIncognitoSessionStorage()

        _uiState.update { state ->
            val remaining = state.tabs.filterNot { it.isPrivate }
            if (remaining.isEmpty()) {
                val fresh = BrowserTab(title = "Home", url = "apex://home")
                state.copy(
                    tabs = listOf(fresh),
                    tabGroups = emptyList(),
                    expandedGroupId = null,
                    editingGroupId = null,
                    activeTabId = fresh.id,
                    isIncognitoUnlocked = false,
                    snackbarNotice = "Incognito session ended • Cookies & site data wiped"
                )
            } else {
                val (cleanTabs, cleanGroups) = normalizeGroups(remaining, state.tabGroups)
                val nextActiveId = if (state.activeTabId in incognitoIds) {
                    cleanTabs.last().id
                } else {
                    state.activeTabId
                }
                state.copy(
                    tabs = cleanTabs,
                    tabGroups = cleanGroups,
                    activeTabId = nextActiveId,
                    isIncognitoUnlocked = false,
                    snackbarNotice = "Incognito session ended • Cookies & site data wiped"
                )
            }
        }
    }

    fun closeSelectedTabs(tabIds: Set<String>) {
        if (tabIds.isEmpty()) return
        val currentTabs = _uiState.value.tabs
        val closingTabs = currentTabs.filter { it.id in tabIds }
        val closedAnyIncognito = closingTabs.any { it.isPrivate }

        closingTabs.forEach { tab ->
            lastRecordedVisitByTab.remove(tab.id)
            webViewRegistry.remove(tab.id)?.let { wv ->
                if (tab.isPrivate) {
                    performanceOptimizer.wipeIncognitoWebView(wv)
                }
                wv.destroy()
            }
        }

        val remainingAfterClose = currentTabs.filterNot { it.id in tabIds }
        val anyIncognitoLeft = remainingAfterClose.any { it.isPrivate }
        if (closedAnyIncognito && !anyIncognitoLeft) {
            performanceOptimizer.wipeIncognitoSessionStorage()
        }

        _uiState.update { state ->
            val remaining = state.tabs.filterNot { it.id in tabIds }
            if (remaining.isEmpty()) {
                val fresh = BrowserTab(title = "Home", url = "apex://home")
                state.copy(
                    tabs = listOf(fresh),
                    tabGroups = emptyList(),
                    expandedGroupId = null,
                    editingGroupId = null,
                    activeTabId = fresh.id,
                    isIncognitoUnlocked = false,
                    snackbarNotice = "Closed ${closingTabs.size} tab${if (closingTabs.size == 1) "" else "s"}"
                )
            } else {
                val (cleanTabs, cleanGroups) = normalizeGroups(remaining, state.tabGroups)
                val nextActiveId = if (state.activeTabId in tabIds) {
                    cleanTabs.last().id
                } else {
                    state.activeTabId
                }
                state.copy(
                    tabs = cleanTabs,
                    tabGroups = cleanGroups,
                    activeTabId = nextActiveId,
                    isIncognitoUnlocked = anyIncognitoLeft,
                    snackbarNotice = "Closed ${closingTabs.size} tab${if (closingTabs.size == 1) "" else "s"}"
                )
            }
        }
    }

    fun groupSelectedTabs(tabIds: Set<String>) {
        if (tabIds.isEmpty()) return
        _uiState.update { state ->
            val newGroupId = "grp_${UUID.randomUUID()}"
            val groupNumber = state.tabGroups.size + 1
            val defaultColor = TabGroupColorOption.entries[
                state.tabGroups.size % TabGroupColorOption.entries.size
            ]
            val newGroup = TabGroupInfo(
                id = newGroupId,
                title = "Group $groupNumber",
                colorOption = defaultColor
            )
            val updatedTabs = state.tabs.map { tab ->
                if (tab.id in tabIds) {
                    tab.copy(groupId = newGroupId, tabColor = tab.tabColor ?: defaultColor)
                } else {
                    tab
                }
            }
            val (cleanTabs, cleanGroups) = normalizeGroups(updatedTabs, state.tabGroups + newGroup)
            state.copy(
                tabs = cleanTabs,
                tabGroups = cleanGroups,
                snackbarNotice = "Grouped ${tabIds.size} tab${if (tabIds.size == 1) "" else "s"} into ${newGroup.title}"
            )
        }
    }

    fun createNewTabGroupFromSwitcher() {
        _uiState.update { state ->
            val newGroupId = "grp_${UUID.randomUUID()}"
            val groupNumber = state.tabGroups.size + 1
            val defaultColor = TabGroupColorOption.entries[
                state.tabGroups.size % TabGroupColorOption.entries.size
            ]
            val newGroup = TabGroupInfo(
                id = newGroupId,
                title = "Group $groupNumber",
                colorOption = defaultColor
            )

            val ungroupedTabs = state.tabs.filter { it.groupId == null }
            val updatedTabs = if (ungroupedTabs.size >= 2) {
                val idsToGroup = ungroupedTabs.takeLast(2).map { it.id }.toSet()
                state.tabs.map { tab ->
                    if (tab.id in idsToGroup) {
                        tab.copy(groupId = newGroupId, tabColor = tab.tabColor ?: defaultColor)
                    } else {
                        tab
                    }
                }
            } else if (ungroupedTabs.size == 1) {
                val existingUngroupedId = ungroupedTabs.single().id
                val companionTab = BrowserTab(
                    title = "Home",
                    url = "apex://home",
                    groupId = newGroupId,
                    tabColor = defaultColor
                )
                state.tabs.map { tab ->
                    if (tab.id == existingUngroupedId) {
                        tab.copy(groupId = newGroupId, tabColor = tab.tabColor ?: defaultColor)
                    } else {
                        tab
                    }
                } + companionTab
            } else {
                val t1 = BrowserTab(title = "Home", url = "apex://home", groupId = newGroupId, tabColor = defaultColor)
                val t2 = BrowserTab(title = "Home", url = "apex://home", groupId = newGroupId, tabColor = defaultColor)
                state.tabs + t1 + t2
            }

            val (cleanTabs, cleanGroups) = normalizeGroups(updatedTabs, state.tabGroups + newGroup)
            state.copy(
                tabs = cleanTabs,
                tabGroups = cleanGroups,
                editingGroupId = newGroupId,
                isTabSwitcherOpen = true
            )
        }
    }

    fun mergeGridEntriesIntoGroup(draggedKey: String, targetKey: String) {
        if (draggedKey == targetKey) return

        _uiState.update { state ->
            val draggedIsGroup = draggedKey.startsWith("group_")
            val targetIsGroup = targetKey.startsWith("group_")

            val draggedId = draggedKey.removePrefix("single_").removePrefix("group_")
            val targetId = targetKey.removePrefix("single_").removePrefix("group_")

            when {
                !draggedIsGroup && !targetIsGroup -> {
                    val newGroupId = "grp_${UUID.randomUUID()}"
                    val groupNumber = state.tabGroups.size + 1
                    val targetTab = state.tabs.firstOrNull { it.id == targetId }
                    val draggedTab = state.tabs.firstOrNull { it.id == draggedId }
                    val chosenColor = targetTab?.tabColor
                        ?: draggedTab?.tabColor
                        ?: TabGroupColorOption.entries[
                            state.tabGroups.size % TabGroupColorOption.entries.size
                        ]
                    val newGroup = TabGroupInfo(
                        id = newGroupId,
                        title = "Group $groupNumber",
                        colorOption = chosenColor
                    )

                    val updatedTabs = state.tabs.map { tab ->
                        if (tab.id == targetId || tab.id == draggedId) {
                            tab.copy(groupId = newGroupId, tabColor = tab.tabColor ?: chosenColor)
                        } else {
                            tab
                        }
                    }
                    normalizeGroups(
                        tabs = updatedTabs,
                        groups = state.tabGroups + newGroup
                    ).let { (cleanTabs, cleanGroups) ->
                        state.copy(tabs = cleanTabs, tabGroups = cleanGroups)
                    }
                }

                !draggedIsGroup && targetIsGroup -> {
                    val targetGroupColor = state.tabGroups.firstOrNull { it.id == targetId }?.colorOption
                    val updatedTabs = state.tabs.map { tab ->
                        if (tab.id == draggedId) {
                            tab.copy(groupId = targetId, tabColor = tab.tabColor ?: targetGroupColor)
                        } else {
                            tab
                        }
                    }
                    normalizeGroups(updatedTabs, state.tabGroups).let { (cleanTabs, cleanGroups) ->
                        state.copy(tabs = cleanTabs, tabGroups = cleanGroups)
                    }
                }

                draggedIsGroup && !targetIsGroup -> {
                    val draggedGroupColor = state.tabGroups.firstOrNull { it.id == draggedId }?.colorOption
                    val updatedTabs = state.tabs.map { tab ->
                        if (tab.id == targetId) {
                            tab.copy(groupId = draggedId, tabColor = tab.tabColor ?: draggedGroupColor)
                        } else {
                            tab
                        }
                    }
                    normalizeGroups(updatedTabs, state.tabGroups).let { (cleanTabs, cleanGroups) ->
                        state.copy(tabs = cleanTabs, tabGroups = cleanGroups)
                    }
                }

                else -> {
                    val targetGroupColor = state.tabGroups.firstOrNull { it.id == targetId }?.colorOption
                    val updatedTabs = state.tabs.map { tab ->
                        if (tab.groupId == draggedId) {
                            tab.copy(groupId = targetId, tabColor = targetGroupColor ?: tab.tabColor)
                        } else {
                            tab
                        }
                    }
                    normalizeGroups(updatedTabs, state.tabGroups).let { (cleanTabs, cleanGroups) ->
                        state.copy(
                            tabs = cleanTabs,
                            tabGroups = cleanGroups,
                            expandedGroupId = if (state.expandedGroupId == draggedId) targetId else state.expandedGroupId
                        )
                    }
                }
            }
        }
    }

    fun moveGroupedTabToGridTarget(tabId: String, targetGridKey: String?) {
        if (targetGridKey == null) {
            ungroupTab(tabId)
            return
        }
        val currentTab = _uiState.value.tabs.firstOrNull { it.id == tabId } ?: return
        val sourceGroupKey = currentTab.groupId?.let { "group_$it" }
        if (targetGridKey == sourceGroupKey || targetGridKey == "single_$tabId") {
            return
        }

        _uiState.update { state ->
            if (targetGridKey.startsWith("group_")) {
                val destGroupId = targetGridKey.removePrefix("group_")
                val destColor = state.tabGroups.firstOrNull { it.id == destGroupId }?.colorOption
                val updatedTabs = state.tabs.map { tab ->
                    if (tab.id == tabId) {
                        tab.copy(groupId = destGroupId, tabColor = destColor ?: tab.tabColor)
                    } else {
                        tab
                    }
                }
                val (cleanTabs, cleanGroups) = normalizeGroups(updatedTabs, state.tabGroups)
                state.copy(tabs = cleanTabs, tabGroups = cleanGroups)
            } else {
                val otherTabId = targetGridKey.removePrefix("single_")
                val newGroupId = "grp_${UUID.randomUUID()}"
                val groupNumber = state.tabGroups.size + 1
                val chosenColor = TabGroupColorOption.entries[
                    state.tabGroups.size % TabGroupColorOption.entries.size
                ]
                val newGroup = TabGroupInfo(
                    id = newGroupId,
                    title = "Group $groupNumber",
                    colorOption = chosenColor
                )
                val updatedTabs = state.tabs.map { tab ->
                    if (tab.id == tabId || tab.id == otherTabId) {
                        tab.copy(groupId = newGroupId, tabColor = chosenColor)
                    } else {
                        tab
                    }
                }
                val (cleanTabs, cleanGroups) = normalizeGroups(updatedTabs, state.tabGroups + newGroup)
                state.copy(tabs = cleanTabs, tabGroups = cleanGroups)
            }
        }
    }

    fun openGroupEditor(groupId: String) {
        _uiState.update { it.copy(editingGroupId = groupId) }
    }

    fun dismissGroupEditor() {
        _uiState.update { it.copy(editingGroupId = null) }
    }

    fun updateGroupTitleAndColor(
        groupId: String,
        newTitle: String,
        colorOption: TabGroupColorOption
    ) {
        _uiState.update { state ->
            val trimmed = newTitle.trim()
            val updatedGroups = state.tabGroups.map { grp ->
                if (grp.id == groupId) {
                    grp.copy(
                        title = trimmed.ifEmpty { grp.title },
                        colorOption = colorOption
                    )
                } else {
                    grp
                }
            }
            val updatedTabs = state.tabs.map { tab ->
                if (tab.groupId == groupId) {
                    tab.copy(tabColor = colorOption)
                } else {
                    tab
                }
            }
            state.copy(
                tabs = updatedTabs,
                tabGroups = updatedGroups,
                editingGroupId = null
            )
        }
    }

    fun cycleTabColor(tabId: String) {
        _uiState.update { state ->
            val updatedTabs = state.tabs.map { tab ->
                if (tab.id == tabId) {
                    val nextColor = tab.tabColor?.next() ?: TabGroupColorOption.COBALT
                    tab.copy(tabColor = nextColor)
                } else {
                    tab
                }
            }
            state.copy(tabs = updatedTabs)
        }
    }

    fun ungroupTab(tabId: String) {
        _uiState.update { state ->
            val updatedTabs = state.tabs.map { tab ->
                if (tab.id == tabId) tab.copy(groupId = null) else tab
            }
            val (cleanTabs, cleanGroups) = normalizeGroups(updatedTabs, state.tabGroups)
            val validExpanded = state.expandedGroupId?.takeIf { expId ->
                cleanGroups.any { it.id == expId }
            }
            val validEditing = state.editingGroupId?.takeIf { editId ->
                cleanGroups.any { it.id == editId }
            }
            state.copy(
                tabs = cleanTabs,
                tabGroups = cleanGroups,
                expandedGroupId = validExpanded,
                editingGroupId = validEditing
            )
        }
    }

    fun ungroupAllInGroup(groupId: String) {
        _uiState.update { state ->
            val updatedTabs = state.tabs.map { tab ->
                if (tab.groupId == groupId) tab.copy(groupId = null) else tab
            }
            val remainingGroups = state.tabGroups.filterNot { it.id == groupId }
            state.copy(
                tabs = updatedTabs,
                tabGroups = remainingGroups,
                expandedGroupId = if (state.expandedGroupId == groupId) null else state.expandedGroupId,
                editingGroupId = if (state.editingGroupId == groupId) null else state.editingGroupId
            )
        }
    }

    fun setExpandedGroup(groupId: String?) {
        _uiState.update { it.copy(expandedGroupId = groupId) }
    }

    fun closeGroup(groupId: String) {
        val currentTabs = _uiState.value.tabs
        val groupTabs = currentTabs.filter { it.groupId == groupId }
        val groupTabIds = groupTabs.map { it.id }.toSet()
        val closedAnyIncognito = groupTabs.any { it.isPrivate }

        groupTabs.forEach { tab ->
            lastRecordedVisitByTab.remove(tab.id)
            webViewRegistry.remove(tab.id)?.let { wv ->
                if (tab.isPrivate) {
                    performanceOptimizer.wipeIncognitoWebView(wv)
                }
                wv.destroy()
            }
        }

        val remainingTabsAfterClose = currentTabs.filterNot { it.id in groupTabIds }
        val incognitoSessionEnded = closedAnyIncognito && remainingTabsAfterClose.none { it.isPrivate }
        if (incognitoSessionEnded) {
            performanceOptimizer.wipeIncognitoSessionStorage()
        }

        _uiState.update { state ->
            val remaining = state.tabs.filterNot { it.id in groupTabIds }
            val sessionNotice = if (incognitoSessionEnded) {
                "Incognito session ended • Cookies & site data wiped"
            } else {
                state.snackbarNotice
            }
            if (remaining.isEmpty()) {
                val fresh = BrowserTab(title = "Home", url = "apex://home")
                state.copy(
                    tabs = listOf(fresh),
                    tabGroups = emptyList(),
                    expandedGroupId = null,
                    editingGroupId = null,
                    activeTabId = fresh.id,
                    snackbarNotice = sessionNotice
                )
            } else {
                val (cleanTabs, cleanGroups) = normalizeGroups(remaining, state.tabGroups)
                val nextActive = if (state.activeTabId in groupTabIds) {
                    cleanTabs.last().id
                } else {
                    state.activeTabId
                }
                state.copy(
                    tabs = cleanTabs,
                    tabGroups = cleanGroups,
                    expandedGroupId = if (state.expandedGroupId == groupId) null else state.expandedGroupId,
                    editingGroupId = if (state.editingGroupId == groupId) null else state.editingGroupId,
                    activeTabId = nextActive,
                    snackbarNotice = sessionNotice
                )
            }
        }
    }

    fun closeTab(tabId: String) {
        val currentTabs = _uiState.value.tabs
        val closingTab = currentTabs.firstOrNull { it.id == tabId }
        lastRecordedVisitByTab.remove(tabId)
        webViewRegistry.remove(tabId)?.let { wv ->
            if (closingTab?.isPrivate == true) {
                performanceOptimizer.wipeIncognitoWebView(wv)
            }
            wv.destroy()
        }

        val remainingAfterClose = currentTabs.filterNot { it.id == tabId }
        val incognitoSessionEnded = closingTab?.isPrivate == true && remainingAfterClose.none { it.isPrivate }
        if (incognitoSessionEnded) {
            performanceOptimizer.wipeIncognitoSessionStorage()
        }

        _uiState.update { state ->
            val closedTab = state.tabs.firstOrNull { it.id == tabId }
            val remaining = state.tabs.filterNot { it.id == tabId }
            val sessionNotice = if (incognitoSessionEnded) {
                "Incognito session ended • Cookies & site data wiped"
            } else {
                state.snackbarNotice
            }
            if (remaining.isEmpty()) {
                val fresh = BrowserTab(title = "Home", url = "apex://home")
                state.copy(
                    tabs = listOf(fresh),
                    tabGroups = emptyList(),
                    expandedGroupId = null,
                    editingGroupId = null,
                    activeTabId = fresh.id,
                    isIncognitoUnlocked = false,
                    snackbarNotice = sessionNotice
                )
            } else {
                val (cleanTabs, cleanGroups) = normalizeGroups(remaining, state.tabGroups)
                val nextActiveId = if (state.activeTabId == tabId) {
                    val closedGroupId = closedTab?.groupId
                    val siblingInGroup = closedGroupId?.let { gId ->
                        cleanTabs.lastOrNull { it.groupId == gId }
                    }
                    siblingInGroup?.id ?: cleanTabs.last().id
                } else {
                    state.activeTabId
                }
                val validExpanded = state.expandedGroupId?.takeIf { expId ->
                    cleanGroups.any { it.id == expId }
                }
                val validEditing = state.editingGroupId?.takeIf { editId ->
                    cleanGroups.any { it.id == editId }
                }
                state.copy(
                    tabs = cleanTabs,
                    tabGroups = cleanGroups,
                    expandedGroupId = validExpanded,
                    editingGroupId = validEditing,
                    activeTabId = nextActiveId,
                    isIncognitoUnlocked = cleanTabs.any { it.isPrivate },
                    snackbarNotice = sessionNotice
                )
            }
        }
    }

    private fun normalizeGroups(
        tabs: List<BrowserTab>,
        groups: List<TabGroupInfo>
    ): Pair<List<BrowserTab>, List<TabGroupInfo>> {
        val countsByGroup = tabs.mapNotNull { it.groupId }.groupingBy { it }.eachCount()
        val knownGroupIds = groups.map { it.id }.toSet()
        val validGroupIds = countsByGroup.filter { (gId, count) ->
            if (gId in knownGroupIds) count >= 1 else count >= 2
        }.keys
        val cleanedTabs = tabs.map { tab ->
            if (tab.groupId != null && tab.groupId !in validGroupIds) {
                tab.copy(groupId = null)
            } else {
                tab
            }
        }
        val cleanedGroups = groups.filter { it.id in validGroupIds }
        return cleanedTabs to cleanedGroups
    }

    fun resetAllTabs() {
        val currentTabs = _uiState.value.tabs
        val hadIncognito = currentTabs.any { it.isPrivate }
        currentTabs.forEach { tab ->
            val wv = webViewRegistry.remove(tab.id)
            if (wv != null) {
                if (tab.isPrivate) {
                    performanceOptimizer.wipeIncognitoWebView(wv)
                }
                wv.destroy()
            }
        }
        webViewRegistry.values.forEach { it.destroy() }
        webViewRegistry.clear()
        lastRecordedVisitByTab.clear()
        if (hadIncognito) {
            performanceOptimizer.wipeIncognitoSessionStorage()
        }
        val fresh = BrowserTab(title = "Home", url = "apex://home")
        _uiState.update {
            it.copy(
                tabs = listOf(fresh),
                tabGroups = emptyList(),
                expandedGroupId = null,
                editingGroupId = null,
                activeTabId = fresh.id,
                isIncognitoUnlocked = false,
                isTabSwitcherOpen = false,
                snackbarNotice = if (hadIncognito) {
                    "All tabs closed • Incognito session data wiped"
                } else {
                    it.snackbarNotice
                }
            )
        }
    }

    // --- Context-Aware Bottom Bar Controls (Home, Bookmarks, Search, Tabs) ---

    fun navigateBack() {
        val active = _uiState.value.activeTab
        val wv = webViewRegistry[active.id]
        if (!active.isHomePage && wv != null && wv.canGoBack()) {
            wv.goBack()
        } else if (!active.isHomePage) {
            wv?.setTag(-19048102, null)
            lastRecordedVisitByTab.remove(active.id)
            _uiState.update { state ->
                state.copy(
                    tabs = state.tabs.map { tab ->
                        if (tab.id == active.id) {
                            tab.copy(url = "apex://home", title = "Home", isLoading = false, blockedCountOnPage = 0)
                        } else {
                            tab
                        }
                    },
                    isToolbarVisible = true
                )
            }
        }
    }

    fun onBottomHomePressed() {
        val currentState = _uiState.value
        webViewRegistry[currentState.activeTabId]?.setTag(-19048102, null)
        lastRecordedVisitByTab.remove(currentState.activeTabId)
        _uiState.update { state ->
            state.copy(
                tabs = state.tabs.map { tab ->
                    if (tab.id == currentState.activeTabId && !tab.isHomePage) {
                        tab.copy(
                            url = "apex://home",
                            title = "Home",
                            isLoading = false,
                            blockedCountOnPage = 0
                        )
                    } else {
                        tab
                    }
                },
                isBookmarksScreenOpen = false,
                isHistoryScreenOpen = false,
                isDownloadsScreenOpen = false,
                isSettingsScreenOpen = false,
                isMenuSheetOpen = false,
                isSearchScreenOpen = false,
                isTabSwitcherOpen = false,
                isAutomationOpen = false,
                isToolbarVisible = true
            )
        }
    }

    fun onBottomBookmarksPressed() {
        val state = _uiState.value
        if (state.isBookmarksScreenOpen) {
            _uiState.update { it.copy(isBookmarksScreenOpen = false, isMenuSheetOpen = false) }
            return
        }
        if (state.activeTab.isHomePage || state.isSettingsScreenOpen || state.isHistoryScreenOpen || state.isDownloadsScreenOpen) {
            _uiState.update {
                it.copy(
                    isBookmarksScreenOpen = true,
                    isHistoryScreenOpen = false,
                    isDownloadsScreenOpen = false,
                    isSettingsScreenOpen = false,
                    isMenuSheetOpen = false,
                    isSearchScreenOpen = false,
                    isTabSwitcherOpen = false,
                    isAutomationOpen = false,
                    isToolbarVisible = true
                )
            }
        } else {
            val active = state.activeTab
            viewModelScope.launch {
                repository.saveBookmark(url = active.url, title = active.title)
                _uiState.update {
                    it.copy(
                        isTabSwitcherOpen = false,
                        isHistoryScreenOpen = false,
                        isDownloadsScreenOpen = false,
                        isSettingsScreenOpen = false,
                        isMenuSheetOpen = false,
                        isSearchScreenOpen = false,
                        snackbarNotice = "Bookmarked '${active.title}'"
                    )
                }
            }
        }
    }

    fun setBookmarksScreenOpen(open: Boolean) {
        _uiState.update {
            it.copy(
                isBookmarksScreenOpen = open,
                isHistoryScreenOpen = if (open) false else it.isHistoryScreenOpen,
                isDownloadsScreenOpen = if (open) false else it.isDownloadsScreenOpen,
                isOfflinePagesScreenOpen = if (open) false else it.isOfflinePagesScreenOpen,
                isMenuSheetOpen = if (open) false else it.isMenuSheetOpen,
                isSettingsScreenOpen = if (open) false else it.isSettingsScreenOpen,
                isSearchScreenOpen = if (open) false else it.isSearchScreenOpen,
                isTabSwitcherOpen = if (open) false else it.isTabSwitcherOpen,
                isToolbarVisible = true
            )
        }
    }

    fun setHistoryScreenOpen(open: Boolean) {
        _uiState.update {
            it.copy(
                isHistoryScreenOpen = open,
                isDownloadsScreenOpen = if (open) false else it.isDownloadsScreenOpen,
                isBookmarksScreenOpen = if (open) false else it.isBookmarksScreenOpen,
                isOfflinePagesScreenOpen = if (open) false else it.isOfflinePagesScreenOpen,
                isMenuSheetOpen = if (open) false else it.isMenuSheetOpen,
                isSettingsScreenOpen = if (open) false else it.isSettingsScreenOpen,
                isSearchScreenOpen = if (open) false else it.isSearchScreenOpen,
                isTabSwitcherOpen = if (open) false else it.isTabSwitcherOpen,
                isAutomationOpen = if (open) false else it.isAutomationOpen,
                isToolbarVisible = true
            )
        }
    }

    fun setDownloadsScreenOpen(open: Boolean) {
        _uiState.update {
            it.copy(
                isDownloadsScreenOpen = open,
                isHistoryScreenOpen = if (open) false else it.isHistoryScreenOpen,
                isBookmarksScreenOpen = if (open) false else it.isBookmarksScreenOpen,
                isOfflinePagesScreenOpen = if (open) false else it.isOfflinePagesScreenOpen,
                isMenuSheetOpen = if (open) false else it.isMenuSheetOpen,
                isSettingsScreenOpen = if (open) false else it.isSettingsScreenOpen,
                isSearchScreenOpen = if (open) false else it.isSearchScreenOpen,
                isTabSwitcherOpen = if (open) false else it.isTabSwitcherOpen,
                isAutomationOpen = if (open) false else it.isAutomationOpen,
                isToolbarVisible = true
            )
        }
    }

    fun startFastDownload(
        url: String,
        suggestedFileName: String? = null,
        contentDisposition: String? = null,
        mimeType: String? = null,
        contentLength: Long = -1L,
        userAgent: String? = null
    ) {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) return
        val engine = fastDownloadEngine
        if (engine != null) {
            engine.enqueueDownload(
                url = cleanUrl,
                suggestedFileName = suggestedFileName,
                contentDisposition = contentDisposition,
                mimeType = mimeType,
                contentLength = contentLength,
                userAgent = userAgent
            ) { entity ->
                _uiState.update {
                    it.copy(snackbarNotice = "Downloading ${entity.fileName} (4× Turbo)…")
                }
            }
        } else {
            val rawName = suggestedFileName?.takeIf { it.isNotBlank() }
                ?: cleanUrl.substringAfterLast('/').substringBefore('?').ifBlank { "download.bin" }
            val resolvedName = FastDownloadEngine.sanitizeFileName(rawName)
            val resolvedMime = FastDownloadEngine.resolveMimeType(cleanUrl, mimeType)
            val category = FastDownloadEngine.inferCategory(resolvedName, resolvedMime)
            viewModelScope.launch {
                val now = System.currentTimeMillis()
                repository.upsertDownload(
                    DownloadItemEntity(
                        fileName = resolvedName,
                        url = cleanUrl,
                        mimeType = resolvedMime,
                        category = category,
                        totalBytes = if (contentLength > 0) contentLength else 128_000L,
                        downloadedBytes = if (contentLength > 0) contentLength else 128_000L,
                        speedBytesPerSec = 4_200_000L,
                        chunksCount = 4,
                        status = FastDownloadEngine.STATUS_COMPLETED,
                        createdAt = now,
                        completedAt = now
                    )
                )
                _uiState.update {
                    it.copy(snackbarNotice = "Downloaded $resolvedName")
                }
            }
        }
    }

    fun pauseDownload(id: Long) {
        fastDownloadEngine?.pauseDownload(id)
    }

    fun resumeDownload(id: Long) {
        fastDownloadEngine?.resumeDownload(id)
    }

    fun retryDownload(id: Long) {
        fastDownloadEngine?.retryDownload(id)
    }

    fun cancelDownload(id: Long) {
        fastDownloadEngine?.cancelDownload(id)
    }

    fun deleteDownload(id: Long) {
        val engine = fastDownloadEngine
        if (engine != null) {
            engine.deleteDownload(id)
        } else {
            viewModelScope.launch { repository.deleteDownloadById(id) }
        }
        _uiState.update { it.copy(snackbarNotice = "Removed download item") }
    }

    fun clearCompletedDownloads() {
        viewModelScope.launch {
            repository.clearCompletedDownloads()
            _uiState.update { it.copy(snackbarNotice = "Cleared completed downloads") }
        }
    }

    fun clearAllDownloads() {
        viewModelScope.launch {
            repository.clearAllDownloads()
            _uiState.update { it.copy(snackbarNotice = "Cleared all downloads") }
        }
    }

    fun deleteBrowsingHistoryItem(id: Long) {
        viewModelScope.launch {
            repository.deleteBrowsingHistoryItem(id)
            _uiState.update { it.copy(snackbarNotice = "Removed visit from history") }
        }
    }

    fun clearBrowsingHistoryRange(sinceMillis: Long?) {
        viewModelScope.launch {
            if (sinceMillis == null) {
                repository.clearAllBrowsingHistory()
                _uiState.update { it.copy(snackbarNotice = "Cleared all browsing history") }
            } else {
                repository.clearBrowsingHistorySince(sinceMillis)
                _uiState.update { it.copy(snackbarNotice = "Cleared selected browsing history range") }
            }
        }
    }

    fun setOfflinePagesScreenOpen(open: Boolean) {
        _uiState.update {
            it.copy(
                isOfflinePagesScreenOpen = open,
                isHistoryScreenOpen = if (open) false else it.isHistoryScreenOpen,
                isDownloadsScreenOpen = if (open) false else it.isDownloadsScreenOpen,
                isBookmarksScreenOpen = if (open) false else it.isBookmarksScreenOpen,
                isMenuSheetOpen = if (open) false else it.isMenuSheetOpen,
                isSettingsScreenOpen = if (open) false else it.isSettingsScreenOpen,
                isSearchScreenOpen = if (open) false else it.isSearchScreenOpen,
                isTabSwitcherOpen = if (open) false else it.isTabSwitcherOpen,
                isAutomationOpen = if (open) false else it.isAutomationOpen,
                isToolbarVisible = true
            )
        }
    }

    fun setSettingsScreenOpen(open: Boolean) {
        _uiState.update {
            it.copy(
                isSettingsScreenOpen = open,
                isHistoryScreenOpen = if (open) false else it.isHistoryScreenOpen,
                isDownloadsScreenOpen = if (open) false else it.isDownloadsScreenOpen,
                isOfflinePagesScreenOpen = if (open) false else it.isOfflinePagesScreenOpen,
                isMenuSheetOpen = if (open) false else it.isMenuSheetOpen,
                isBookmarksScreenOpen = if (open) false else it.isBookmarksScreenOpen,
                isSearchScreenOpen = if (open) false else it.isSearchScreenOpen,
                isTabSwitcherOpen = if (open) false else it.isTabSwitcherOpen,
                isAutomationOpen = if (open) false else it.isAutomationOpen,
                isToolbarVisible = true
            )
        }
    }

    fun setMenuSheetOpen(open: Boolean) {
        _uiState.update { it.copy(isMenuSheetOpen = open) }
    }

    fun toggleMenuSheet() {
        _uiState.update { it.copy(isMenuSheetOpen = !it.isMenuSheetOpen) }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch { repository.deleteBookmark(id) }
    }

    fun importBookmarksFromText(rawContent: String) {
        viewModelScope.launch {
            val importedCount = repository.importBookmarksFromRawContent(rawContent)
            val message = if (importedCount > 0) {
                "Imported $importedCount bookmark${if (importedCount == 1) "" else "s"}"
            } else {
                "No valid bookmarks found to import"
            }
            _uiState.update { it.copy(snackbarNotice = message) }
        }
    }

    fun notifyBookmarksExported(count: Int, formatLabel: String) {
        _uiState.update {
            it.copy(
                snackbarNotice = "Exported $count bookmark${if (count == 1) "" else "s"} ($formatLabel)"
            )
        }
    }

    fun addTopVisit(title: String, url: String) {
        viewModelScope.launch {
            repository.addTopVisit(title, url)
            _uiState.update { it.copy(snackbarNotice = "Added to Top Visits") }
        }
    }

    fun deleteTopVisit(id: Long) {
        viewModelScope.launch {
            repository.deleteTopVisit(id)
            _uiState.update { it.copy(snackbarNotice = "Removed from Top Visits") }
        }
    }

    fun saveCurrentPageForOffline() {
        val active = _uiState.value.activeTab
        if (active.isHomePage) {
            _uiState.update {
                it.copy(
                    snackbarNotice = "Home page is already available offline",
                    isMenuSheetOpen = false
                )
            }
            return
        }

        // Built-in showcase pages
        val builtIn = com.example.engine.BuiltInShowcasePages.resolveHtml(active.url)
        if (builtIn != null) {
            viewModelScope.launch {
                repository.saveOfflinePage(
                    url = active.url,
                    title = active.title,
                    htmlContent = builtIn.second
                )
                _uiState.update {
                    it.copy(
                        snackbarNotice = "Saved '${active.title}' for offline viewing",
                        isMenuSheetOpen = false
                    )
                }
            }
            return
        }

        val wv = webViewRegistry[active.id]
        if (wv != null) {
            wv.evaluateJavascript(
                "(function() { return document.documentElement ? document.documentElement.outerHTML : (document.body ? document.body.outerHTML : ''); })();"
            ) { rawOutput ->
                val html = OfflineStorageUtil.unescapeJsString(rawOutput)
                if (html.isNotBlank() && html.length > 30) {
                    viewModelScope.launch {
                        repository.saveOfflinePage(
                            url = active.url,
                            title = active.title,
                            htmlContent = html
                        )
                        _uiState.update {
                            it.copy(
                                snackbarNotice = "Saved '${active.title}' for offline viewing",
                                isMenuSheetOpen = false
                            )
                        }
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            snackbarNotice = "Unable to capture page content for offline use",
                            isMenuSheetOpen = false
                        )
                    }
                }
            }
        } else {
            _uiState.update {
                it.copy(
                    snackbarNotice = "Page not ready to save offline",
                    isMenuSheetOpen = false
                )
            }
        }
    }

    fun loadOfflinePage(url: String, newTab: Boolean = false) {
        viewModelScope.launch {
            val offlinePage = repository.getOfflinePageByUrl(url)
            if (offlinePage == null) {
                _uiState.update { it.copy(snackbarNotice = "Saved page content not found") }
                return@launch
            }

            if (newTab) {
                val newTabId = "tab_${UUID.randomUUID()}"
                val newTabObj = BrowserTab(
                    id = newTabId,
                    title = offlinePage.title,
                    url = offlinePage.url,
                    isOfflineCopy = true,
                    offlineSavedAt = offlinePage.savedAt,
                    offlineHtmlContent = offlinePage.htmlContent,
                    isLoading = false,
                    progress = 1f
                )
                _uiState.update { state ->
                    state.copy(
                        previousTabIndex = state.activeTabIndex,
                        tabs = state.tabs + newTabObj,
                        activeTabId = newTabId,
                        isOfflinePagesScreenOpen = false,
                        isMenuSheetOpen = false,
                        isSearchScreenOpen = false,
                        isBookmarksScreenOpen = false,
                        isSettingsScreenOpen = false,
                        isTabSwitcherOpen = false,
                        isToolbarVisible = true,
                        snackbarNotice = "Viewing offline copy saved on ${OfflineStorageUtil.formatSavedTimestamp(offlinePage.savedAt)}"
                    )
                }
            } else {
                val activeId = _uiState.value.activeTabId
                _uiState.update { state ->
                    state.copy(
                        tabs = state.tabs.map { tab ->
                            if (tab.id == activeId) {
                                tab.copy(
                                    title = offlinePage.title,
                                    url = offlinePage.url,
                                    isOfflineCopy = true,
                                    offlineSavedAt = offlinePage.savedAt,
                                    offlineHtmlContent = offlinePage.htmlContent,
                                    isLoading = false,
                                    progress = 1f
                                )
                            } else {
                                tab
                            }
                        },
                        isOfflinePagesScreenOpen = false,
                        isMenuSheetOpen = false,
                        isSearchScreenOpen = false,
                        isBookmarksScreenOpen = false,
                        isSettingsScreenOpen = false,
                        isTabSwitcherOpen = false,
                        isToolbarVisible = true,
                        snackbarNotice = "Viewing offline copy saved on ${OfflineStorageUtil.formatSavedTimestamp(offlinePage.savedAt)}"
                    )
                }
                val wv = webViewRegistry[activeId]
                wv?.loadDataWithBaseURL(
                    offlinePage.url,
                    offlinePage.htmlContent,
                    "text/html",
                    "UTF-8",
                    null
                )
            }
        }
    }

    fun onPageLoadFailed(tabId: String, failedUrl: String) {
        val trimmed = failedUrl.trim()
        if (trimmed.isBlank() || trimmed == "about:blank" || trimmed.startsWith("apex://")) return

        viewModelScope.launch {
            val offlinePage = repository.getOfflinePageByUrl(trimmed)
            if (offlinePage != null) {
                _uiState.update { state ->
                    state.copy(
                        tabs = state.tabs.map { tab ->
                            if (tab.id == tabId) {
                                tab.copy(
                                    title = offlinePage.title,
                                    url = offlinePage.url,
                                    isOfflineCopy = true,
                                    offlineSavedAt = offlinePage.savedAt,
                                    offlineHtmlContent = offlinePage.htmlContent,
                                    isLoading = false,
                                    progress = 1f
                                )
                            } else {
                                tab
                            }
                        },
                        snackbarNotice = "Network unavailable. Showing saved offline copy."
                    )
                }
                val wv = webViewRegistry[tabId]
                wv?.loadDataWithBaseURL(
                    offlinePage.url,
                    offlinePage.htmlContent,
                    "text/html",
                    "UTF-8",
                    null
                )
            }
        }
    }

    fun reloadOnline(tabId: String) {
        val targetTab = _uiState.value.tabs.firstOrNull { it.id == tabId } ?: return
        _uiState.update { state ->
            state.copy(
                tabs = state.tabs.map { tab ->
                    if (tab.id == tabId) {
                        tab.copy(
                            isOfflineCopy = false,
                            offlineSavedAt = null,
                            offlineHtmlContent = null,
                            isLoading = true,
                            progress = 0.1f
                        )
                    } else {
                        tab
                    }
                }
            )
        }
        val wv = webViewRegistry[tabId]
        if (wv != null) {
            val builtIn = com.example.engine.BuiltInShowcasePages.resolveHtml(targetTab.url)
            if (builtIn != null) {
                wv.loadDataWithBaseURL(builtIn.first, builtIn.second, "text/html", "UTF-8", null)
            } else {
                wv.reload()
            }
        }
    }

    fun deleteOfflinePage(id: Long) {
        viewModelScope.launch {
            repository.deleteOfflinePage(id)
            _uiState.update { it.copy(snackbarNotice = "Deleted offline page") }
        }
    }

    fun clearAllOfflinePages() {
        viewModelScope.launch {
            repository.clearOfflinePages()
            _uiState.update { it.copy(snackbarNotice = "Cleared all offline pages") }
        }
    }

    fun setAutoSaveOfflinePagesEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setAutoSaveOfflinePagesEnabled(enabled)
        }
    }

    fun setShowTopVisitsOnHome(enabled: Boolean) {
        viewModelScope.launch {
            repository.setShowTopVisitsOnHome(enabled)
        }
    }

    fun reloadActiveTab() {
        val active = _uiState.value.activeTab
        if (active.isHomePage) return
        if (active.isOfflineCopy) {
            reloadOnline(active.id)
            return
        }
        val builtIn = com.example.engine.BuiltInShowcasePages.resolveHtml(active.url)
        val wv = webViewRegistry[active.id]
        if (wv != null) {
            if (builtIn != null) {
                wv.loadDataWithBaseURL(builtIn.first, builtIn.second, "text/html", "UTF-8", null)
            } else {
                wv.reload()
            }
        }
    }

    fun captureTabThumbnail(tabId: String): Bitmap? {
        val tab = _uiState.value.tabs.firstOrNull { it.id == tabId }
        if (tab?.isPrivate == true) return null
        val wv = webViewRegistry[tabId] ?: return null
        return runCatching {
            if (wv.width > 0 && wv.height > 0) {
                val thumbWidth = 320
                val thumbHeight = (wv.height.toFloat() / wv.width.toFloat() * thumbWidth).toInt().coerceIn(200, 480)
                val fullBitmap = Bitmap.createBitmap(wv.width, wv.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(fullBitmap)
                wv.draw(canvas)
                val scaled = Bitmap.createScaledBitmap(fullBitmap, thumbWidth, thumbHeight, true)
                fullBitmap.recycle()
                scaled
            } else null
        }.getOrNull()
    }

    fun setTabSwitcherOpen(open: Boolean) {
        if (open) {
            val currentTabId = _uiState.value.activeTabId
            val thumb = captureTabThumbnail(currentTabId)
            if (thumb != null) {
                _uiState.update { state ->
                    state.copy(
                        tabs = state.tabs.map { tab ->
                            if (tab.id == currentTabId) {
                                tab.copy(
                                    previewThumbnail = thumb,
                                    lastActiveTimeMillis = System.currentTimeMillis()
                                )
                            } else tab
                        }
                    )
                }
            }
        }
        _uiState.update {
            it.copy(
                isTabSwitcherOpen = open,
                expandedGroupId = if (!open) null else it.expandedGroupId,
                isMenuSheetOpen = if (open) false else it.isMenuSheetOpen,
                isBookmarksScreenOpen = if (open) false else it.isBookmarksScreenOpen,
                isHistoryScreenOpen = if (open) false else it.isHistoryScreenOpen,
                isDownloadsScreenOpen = if (open) false else it.isDownloadsScreenOpen,
                isOfflinePagesScreenOpen = if (open) false else it.isOfflinePagesScreenOpen,
                isSettingsScreenOpen = if (open) false else it.isSettingsScreenOpen,
                isSearchScreenOpen = if (open) false else it.isSearchScreenOpen,
                isToolbarVisible = true
            )
        }
    }

    fun setAutomationOpen(open: Boolean, module: ExtensionModule = _uiState.value.activeExtension) {
        _uiState.update {
            it.copy(
                isAutomationOpen = open,
                activeExtension = module,
                isSettingsScreenOpen = if (open) false else it.isSettingsScreenOpen,
                isSearchScreenOpen = if (open) false else it.isSearchScreenOpen,
                isToolbarVisible = true
            )
        }
    }

    fun selectExtensionModule(module: ExtensionModule) {
        _uiState.update { it.copy(activeExtension = module) }
    }

    // --- Preferences & Settings Controls ---

    fun toggleShields(enabled: Boolean) {
        viewModelScope.launch { repository.setShieldsEnabled(enabled) }
    }

    fun toggleHttpsUpgrade(enabled: Boolean) {
        viewModelScope.launch { repository.setHttpsUpgradeEnabled(enabled) }
    }

    fun toggleAggressivePreload(enabled: Boolean) {
        viewModelScope.launch { repository.setAggressivePreloadEnabled(enabled) }
    }

    fun toggleScriptGuard(enabled: Boolean) {
        viewModelScope.launch { repository.setScriptGuardEnabled(enabled) }
    }

    fun toggleDesktopViewport(enabled: Boolean) {
        viewModelScope.launch {
            repository.setDesktopViewportEnabled(enabled)
            val currentPrefs = preferencesState.value
            val tabsById = _uiState.value.tabs.associateBy { it.id }
            webViewRegistry.forEach { (tabId, wv) ->
                val isPrivate = tabsById[tabId]?.isPrivate == true
                performanceOptimizer.configureFastWebView(
                    webView = wv,
                    scriptGuardEnabled = currentPrefs.scriptGuardEnabled,
                    desktopMode = enabled,
                    aggressiveCaching = currentPrefs.aggressivePreloadEnabled,
                    isIncognito = isPrivate
                )
                performanceOptimizer.applyDesktopViewportMode(wv, enabled)
            }
            _uiState.update {
                it.copy(
                    snackbarNotice = if (enabled) "Desktop site enabled" else "Mobile site enabled"
                )
            }
        }
    }

    fun toggleDynamicColor(enabled: Boolean) {
        viewModelScope.launch { repository.setDynamicColorEnabled(enabled) }
    }

    fun selectSeedPalette(preset: SeedPalettePreset) {
        viewModelScope.launch { repository.setSeedPaletteOrdinal(preset.ordinal) }
    }

    fun cycleSeedTheme() {
        viewModelScope.launch { repository.cycleSeedPalette() }
    }

    fun resetPrivacyStats() {
        viewModelScope.launch {
            repository.resetTelemetryStats()
            performanceOptimizer.wipeIncognitoSessionStorage()
            _uiState.update {
                it.copy(snackbarNotice = "Search history and browsing data cleared")
            }
        }
    }

    // --- Extension 1: Nano Multi-Agent Automation ---

    fun runNanoTask(userPrompt: String) {
        val cleanPrompt = userPrompt.trim()
        if (cleanPrompt.isEmpty() || _uiState.value.isNanoRunning) return

        val userMsg = NanoChatMessage(
            id = "u_${System.currentTimeMillis()}",
            isUser = true,
            text = cleanPrompt
        )
        val agentMsgId = "a_${System.currentTimeMillis()}"
        val placeholderAgentMsg = NanoChatMessage(
            id = agentMsgId,
            isUser = false,
            text = "Executing multi-agent DOM workflow…",
            steps = emptyList(),
            isRunning = true
        )

        _uiState.update {
            it.copy(
                nanoMessages = it.nanoMessages + userMsg + placeholderAgentMsg,
                isNanoRunning = true
            )
        }

        viewModelScope.launch {
            val startTabUrl = _uiState.value.activeTab.url
            val outcome = nanoEngine.runTask(
                prompt = cleanPrompt,
                currentUrl = startTabUrl,
                webViewProvider = { webViewRegistry[_uiState.value.activeTabId] },
                onNavigateUrl = { destUrl ->
                    submitNavigation(destUrl, _uiState.value.activeTabId)
                },
                onStepUpdate = { liveSteps ->
                    _uiState.update { state ->
                        state.copy(
                            nanoMessages = state.nanoMessages.map { msg ->
                                if (msg.id == agentMsgId) msg.copy(steps = liveSteps) else msg
                            }
                        )
                    }
                }
            )

            _uiState.update { state ->
                state.copy(
                    isNanoRunning = false,
                    nanoMessages = state.nanoMessages.map { msg ->
                        if (msg.id == agentMsgId) {
                            msg.copy(
                                text = outcome.finalSummary,
                                isRunning = false
                            )
                        } else {
                            msg
                        }
                    }
                )
            }
        }
    }

    // --- Extension 2: Retriever Local-First Web Scraper ---

    fun onRetrieverPromptChanged(value: String) {
        _uiState.update { it.copy(retrieverPromptInput = value) }
    }

    fun selectExtractionRule(ruleId: Long) {
        val rule = extractionRulesState.value.firstOrNull { it.id == ruleId }
        _uiState.update {
            it.copy(
                selectedRuleId = ruleId,
                retrieverPromptInput = rule?.promptDescription ?: it.retrieverPromptInput
            )
        }
    }

    fun setRetrieverDisplayMode(mode: RetrieverDisplayMode) {
        _uiState.update { it.copy(retrieverDisplayMode = mode) }
    }

    fun saveCurrentRetrieverRule() {
        val input = _uiState.value.retrieverPromptInput.trim()
        if (input.isEmpty()) return
        viewModelScope.launch {
            val built = retrieverEngine.buildRuleFromInput(input)
            val newId = repository.saveExtractionRule(built)
            _uiState.update {
                it.copy(
                    selectedRuleId = newId,
                    retrieverStatusBanner = "Saved rule '${built.name}' locally"
                )
            }
        }
    }

    fun deleteRetrieverRule(ruleId: Long) {
        viewModelScope.launch {
            repository.deleteExtractionRule(ruleId)
            if (_uiState.value.selectedRuleId == ruleId) {
                _uiState.update { it.copy(selectedRuleId = null) }
            }
        }
    }

    fun captureCurrentPageWithRetriever() {
        val state = _uiState.value
        if (state.isRetrieverRunning) return
        val rule = resolveActiveRule(state)
        val activeTab = state.activeTab

        _uiState.update {
            it.copy(
                isRetrieverRunning = true,
                retrieverStatusBanner = "Capturing DOM from '${activeTab.title}'…"
            )
        }

        viewModelScope.launch {
            val target = TabSnapshotTarget(
                tabId = activeTab.id,
                title = activeTab.title,
                url = activeTab.url
            )
            val records = retrieverEngine.extractFromSingleTab(
                target = target,
                webView = webViewRegistry[activeTab.id],
                rule = rule
            )
            if (rule.id != 0L) {
                repository.updateRuleRunCount(rule.id, records.size)
            }
            _uiState.update {
                it.copy(
                    isRetrieverRunning = false,
                    extractedRecords = records,
                    retrieverStatusBanner = "Captured ${records.size} structured rows from '${activeTab.title}'"
                )
            }
        }
    }

    fun runParallelMultiTabExtraction() {
        val state = _uiState.value
        if (state.isRetrieverRunning) return
        val rule = resolveActiveRule(state)
        val targets = state.tabs.map {
            TabSnapshotTarget(tabId = it.id, title = it.title, url = it.url)
        }

        _uiState.update {
            it.copy(
                isRetrieverRunning = true,
                retrieverStatusBanner = "Running parallel DOM extraction across ${targets.size} open tabs…"
            )
        }

        viewModelScope.launch {
            val records = retrieverEngine.extractAcrossTabsInParallel(
                tabs = targets,
                webViewLookup = { id -> webViewRegistry[id] },
                rule = rule
            )
            if (rule.id != 0L) {
                repository.updateRuleRunCount(rule.id, records.size)
            }
            _uiState.update {
                it.copy(
                    isRetrieverRunning = false,
                    extractedRecords = records,
                    retrieverStatusBanner = "Parallel run complete: ${records.size} rows across ${targets.size} tabs"
                )
            }
        }
    }

    private fun resolveActiveRule(state: BrowserUiState): ExtractionRuleEntity {
        val rules = extractionRulesState.value
        val byId = state.selectedRuleId?.let { id -> rules.firstOrNull { it.id == id } }
        if (byId != null && (state.retrieverPromptInput.isBlank() || state.retrieverPromptInput == byId.promptDescription)) {
            return byId
        }
        if (state.retrieverPromptInput.isNotBlank()) {
            return retrieverEngine.buildRuleFromInput(state.retrieverPromptInput)
        }
        return rules.firstOrNull() ?: retrieverEngine.buildRuleFromInput("Extract headlines, prices, and links")
    }

    private fun mapBaseUrlBackToScheme(loadedUrl: String, currentTabUrl: String): String {
        return when {
            loadedUrl.startsWith("https://news.apex.browser") -> "apex://news"
            loadedUrl.startsWith("https://store.apex.browser") -> "apex://store"
            currentTabUrl.startsWith("apex://") && loadedUrl == "about:blank" -> currentTabUrl
            else -> loadedUrl
        }
    }

    private fun deriveShortTitle(url: String): String {
        return when (url.lowercase()) {
            "apex://home" -> "Home"
            "apex://news" -> "AI News Pulse"
            "apex://store" -> "Apex Gear Store"
            else -> url.removePrefix("https://")
                .removePrefix("http://")
                .removePrefix("www.")
                .substringBefore('/')
                .ifBlank { "Web Tab" }
        }
    }

    override fun onCleared() {
        super.onCleared()
        suggestionJob?.cancel()
        val hadIncognito = _uiState.value.tabs.any { it.isPrivate }
        _uiState.value.tabs.forEach { tab ->
            val wv = webViewRegistry.remove(tab.id)
            if (wv != null) {
                if (tab.isPrivate) {
                    performanceOptimizer.wipeIncognitoWebView(wv)
                }
                wv.destroy()
            }
        }
        webViewRegistry.values.forEach { it.destroy() }
        webViewRegistry.clear()
        if (hadIncognito) {
            performanceOptimizer.wipeIncognitoSessionStorage()
        }
    }

    companion object {
        fun provideFactory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val database = ApexDatabase.getInstance(appContext)
                    val prefsRepo = UserPreferencesRepository(appContext)
                    val sharedPrefs = appContext.getSharedPreferences(
                        "apex_browser_storage",
                        Context.MODE_PRIVATE
                    )
                    val repo = BrowserRepository(database.apexDao(), prefsRepo, sharedPrefs)
                    val downloadEngine = FastDownloadEngine(appContext, repo)
                    return BrowserViewModel(
                        repository = repo,
                        fastDownloadEngine = downloadEngine
                    ) as T
                }
            }
        }
    }
}
