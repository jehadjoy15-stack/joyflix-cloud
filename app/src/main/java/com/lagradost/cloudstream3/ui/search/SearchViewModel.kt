package com.lagradost.cloudstream3.ui.search

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKeys
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.debugAssert
import com.lagradost.cloudstream3.mvvm.debugWarning
import com.lagradost.cloudstream3.mvvm.launchSafe
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.DataStoreHelper.currentAccount
import com.lagradost.cloudstream3.utils.StreamVerificationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


data class ExpandableSearchList(
    var list: List<SearchResponse>, var currentPage: Int, var hasNext: Boolean,
)

const val SEARCH_HISTORY_KEY = "search_history"

class SearchViewModel : ViewModel() {
    private val _searchResponse: MutableLiveData<Resource<ExpandableSearchList>> =
        MutableLiveData()
    val searchResponse: LiveData<Resource<ExpandableSearchList>> get() = _searchResponse

    private val _currentSearch: MutableLiveData<Map<String, ExpandableSearchList>> =
        MutableLiveData()
    val currentSearch: LiveData<Map<String, ExpandableSearchList>> get() = _currentSearch

    private val _currentHistory: MutableLiveData<List<SearchHistoryItem>> = MutableLiveData()
    val currentHistory: LiveData<List<SearchHistoryItem>> get() = _currentHistory

    private val _searchSuggestions: MutableLiveData<List<String>> = MutableLiveData()
    val searchSuggestions: LiveData<List<String>> get() = _searchSuggestions

    private var suggestionJob: Job? = null

    private var repos = apis.withLock { apis.map { APIRepository(it) } }

    fun clearSearch() {
        _searchResponse.postValue(Resource.Success(ExpandableSearchList(emptyList(), 0, false)))
        _currentSearch.postValue(emptyMap())
        expandableSearches.clear()
    }

    var lastQuery: String? = null

    /** Save which providers can searched again and which search result page they are on.
     * Maps provider name to search list.
     * @see [HomeViewModel.expandable] */
    private val expandableSearches: MutableMap<String, ExpandableSearchList> = mutableMapOf()

    private var currentSearchIndex = 0
    private var onGoingSearch: Job? = null

    fun reloadRepos() {
        repos = apis.withLock { apis.map { APIRepository(it) } }
    }

    fun searchAndCancel(
        query: String,
        providersActive: Set<String> = setOf(),
        ignoreSettings: Boolean = false,
        isQuickSearch: Boolean = false,
    ) {
        currentSearchIndex++
        onGoingSearch?.cancel()
        onGoingSearch = search(query, providersActive, ignoreSettings, isQuickSearch)
    }

    fun updateHistory() = ioSafe {
        val items = getKeys("$currentAccount/$SEARCH_HISTORY_KEY")?.mapNotNull {
            getKey<SearchHistoryItem>(it)
        }?.sortedByDescending { it.searchedAt } ?: emptyList()
        _currentHistory.postValue(items)
    }

    /**
     * Fetches search suggestions with debouncing.
     * Waits 300ms before making the API call to avoid too many requests.
     * 
     * @param query The search query to get suggestions for
     */
    fun fetchSuggestions(query: String) {
        suggestionJob?.cancel()
        
        if (query.isBlank() || query.length < 2) {
            _searchSuggestions.postValue(emptyList())
            return
        }
        
        suggestionJob = ioSafe {
            delay(300) // Debounce
            val suggestions = SearchSuggestionApi.getSuggestions(query)
            _searchSuggestions.postValue(suggestions)
        }
    }

    /**
     * Clears the current search suggestions.
     */
    fun clearSuggestions() {
        suggestionJob?.cancel()
        _searchSuggestions.postValue(emptyList())
    }

    private val lock: MutableSet<String> = mutableSetOf()

    // ExpandableHomepageList because the home adapter is reused in the search fragment
    suspend fun expandAndReturn(name: String): HomeViewModel.ExpandableHomepageList? {
        if (lock.contains(name)) return null
        val query = lastQuery ?: return null
        val repo = repos.find { it.name == name } ?: return null

        lock += name

        expandableSearches[name]?.let { current ->
            debugAssert({ !current.hasNext }) {
                "Expand called when not needed"
            }

            val nextPage = current.currentPage + 1
            val next = repo.search(query, nextPage)
            if (next is Resource.Success) {
                val nextValue = next.value
                val playableItems = StreamVerificationManager.filterPlayable(nextValue.items)
                expandableSearches[name]?.apply {
                    this.hasNext = nextValue.hasNext
                    this.currentPage = nextPage

                    debugWarning({ nextValue.items.any { outer -> this.list.any { it.url == outer.url } } }) {
                        "Expanded search contained an item that was previously already in the list.\nQuery = $query, ${nextValue.items} = ${this.list}"
                    }

                    // just to be sure we are not adding the same shit for some reason
                    // Avoids weird behavior in the recyclerview by recreating the list
                    this.list = rankSearchResults((this.list + playableItems).distinctBy { it.url }, query)
                } ?: debugWarning {
                    "Expanded an item not in search load named $name, current list is ${expandableSearches.keys}"
                }
            } else {
                current.hasNext = false
            }

            _searchResponse.postValue(Resource.Success(bundleSearch(expandableSearches, query)))
            _currentSearch.postValue(expandableSearches)
        }

        lock -= name

        val item = expandableSearches[name] ?: return null
        return HomeViewModel.ExpandableHomepageList(
            HomePageList(name, item.list),
            item.currentPage,
            item.hasNext
        )
    }

    private fun bundleSearch(
        lists: MutableMap<String, ExpandableSearchList>,
        query: String? = lastQuery
    ): ExpandableSearchList {
        val allItems = ArrayList<SearchResponse>()
        lists.values.forEach {
            allItems.addAll(it.list)
        }

        if (query.isNullOrBlank()) {
            return ExpandableSearchList(allItems.distinctBy { it.url }, 1, false)
        }

        val rankedList = rankSearchResults(allItems, query)
        return ExpandableSearchList(rankedList, 1, false)
    }

    private fun search(
        query: String,
        providersActive: Set<String>,
        ignoreSettings: Boolean = false,
        isQuickSearch: Boolean = false,
    ) =
        viewModelScope.launchSafe {
            val currentIndex = currentSearchIndex
            if (query.length <= 1) {
                clearSearch()
                return@launchSafe
            }

            if (!isQuickSearch) {
                val key = query.hashCode().toString()
                setKey(
                    "$currentAccount/$SEARCH_HISTORY_KEY",
                    key,
                    SearchHistoryItem(
                        searchedAt = System.currentTimeMillis(),
                        searchText = query,
                        type = emptyList(), // TODO implement tv type
                        key = key,
                    )
                )
            }

            _searchResponse.postValue(Resource.Loading())
            _currentSearch.postValue(emptyMap())
            expandableSearches.clear()

            lastQuery = query

            withContext(Dispatchers.IO) { // This interrupts UI otherwise
                repos.filter { a ->
                    (ignoreSettings || (providersActive.isEmpty() || providersActive.contains(a.name))) && (!isQuickSearch || a.hasQuickSearch)
                }.amap { a -> // Parallel
                    val search = if (isQuickSearch) a.quickSearch(query) else a.search(query, 1)
                    if (currentSearchIndex != currentIndex) return@amap
                    if (search is Resource.Success) {
                        val searchValue = search.value
                        val sortedItems = rankSearchResults(searchValue.items, query)
                        val playableItems = StreamVerificationManager.filterPlayable(sortedItems)
                        if (playableItems.isNotEmpty()) {
                            expandableSearches[a.name] =
                                ExpandableSearchList(playableItems, 1, searchValue.hasNext)
                        }
                    }

                    _currentSearch.postValue(expandableSearches)
                }

                if (currentSearchIndex != currentIndex) return@withContext // this should prevent rewrite of existing data bug

                _currentSearch.postValue(expandableSearches)
                val list = bundleSearch(expandableSearches, query)

                _searchResponse.postValue(Resource.Success(list))
            }
        }

    companion object {
        /**
         * Calculates a relevance score for a search result based on the search query.
         * Exact matches and titles starting with the query receive the highest priority.
         */
        fun calculateRelevanceScore(name: String, query: String, hasPoster: Boolean = true): Double {
            val q = query.trim().lowercase()
            val title = name.trim().lowercase()
            if (q.isEmpty() || title.isEmpty()) return 0.0

            val cleanQuery = q.replace(Regex("[^a-z0-9\\s]"), " ").replace(Regex("\\s+"), " ").trim()
            val cleanTitle = title.replace(Regex("[^a-z0-9\\s]"), " ").replace(Regex("\\s+"), " ").trim()
            val titleWithoutYear = cleanTitle.replace(Regex("\\b(19|20)\\d{2}\\b"), "").replace(Regex("\\s+"), " ").trim()

            var score = when {
                // Exact match (highest priority, e.g. "Jawan" == "Jawan")
                title == q || cleanTitle == cleanQuery -> 1000.0
                titleWithoutYear == cleanQuery -> 920.0

                // Starts with the query (e.g. "Avatar: The Way of Water" starts with "Avatar")
                title.startsWith(q) || cleanTitle.startsWith(cleanQuery) -> {
                    val lengthDiff = (title.length - q.length).coerceAtLeast(0)
                    800.0 - (lengthDiff * 2.0).coerceAtMost(250.0)
                }

                // Query appears as an exact full word/phrase in title (e.g. "The Avatar")
                Regex("\\b${Regex.escape(cleanQuery)}\\b").containsMatchIn(cleanTitle) -> {
                    val lengthDiff = (title.length - q.length).coerceAtLeast(0)
                    600.0 - (lengthDiff * 1.5).coerceAtMost(200.0)
                }

                // Substring match anywhere in title
                title.contains(q) || cleanTitle.contains(cleanQuery) -> {
                    val lengthDiff = (title.length - q.length).coerceAtLeast(0)
                    450.0 - (lengthDiff * 1.5).coerceAtMost(200.0)
                }

                else -> {
                    val queryWords = cleanQuery.split(" ").filter { it.isNotBlank() }
                    if (queryWords.size > 1 && queryWords.all { cleanTitle.contains(it) }) {
                        350.0 - ((title.length - q.length).coerceAtLeast(0).coerceAtMost(150)).toDouble()
                    } else if (queryWords.isNotEmpty()) {
                        val matched = queryWords.count { cleanTitle.contains(it) }
                        if (matched > 0) {
                            (matched.toDouble() / queryWords.size) * 200.0
                        } else {
                            0.0
                        }
                    } else {
                        0.0
                    }
                }
            }

            if (hasPoster) {
                score += 15.0
            }

            return score
        }

        /**
         * Ranks search results so that exact title matches (e.g. "Avatar", "Jawan")
         * appear at the very top of the list.
         */
        fun rankSearchResults(
            items: List<SearchResponse>,
            query: String
        ): List<SearchResponse> {
            val q = query.trim().lowercase()
            if (q.isEmpty()) return items.distinctBy { it.url }

            return items
                .distinctBy { it.url }
                .mapIndexed { index, response ->
                    val relevance = calculateRelevanceScore(
                        name = response.name,
                        query = q,
                        hasPoster = !response.posterUrl.isNullOrBlank()
                    )
                    // Slight penalty for being further down in original provider list
                    val positionPenalty = (index * 0.1).coerceAtMost(20.0)
                    val finalScore = relevance - positionPenalty
                    Pair(response, finalScore)
                }
                .sortedByDescending { it.second }
                .map { it.first }
        }
    }
}
