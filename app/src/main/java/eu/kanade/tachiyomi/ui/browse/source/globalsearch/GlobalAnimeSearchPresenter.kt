package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import android.os.Bundle
import eu.kanade.tachiyomi.data.database.AnimeDatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Anime
import eu.kanade.tachiyomi.data.database.models.toAnimeInfo
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.AnimesPage
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SAnime
import eu.kanade.tachiyomi.source.model.toSAnime
import eu.kanade.tachiyomi.ui.base.presenter.BasePresenter
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourcePresenter
import eu.kanade.tachiyomi.util.lang.runAsObservable
import rx.Observable
import rx.Subscription
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
import rx.subjects.PublishSubject
import timber.log.Timber
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

/**
 * Presenter of [GlobalAnimeSearchController]
 * Function calls should be done from here. UI calls should be done from the controller.
 *
 * @param sourceManager manages the different sources.
 * @param db manages the database calls.
 * @param preferences manages the preference calls.
 */
open class GlobalAnimeSearchPresenter(
    val initialQuery: String? = "",
    val initialExtensionFilter: String? = null,
    val sourceManager: SourceManager = Injekt.get(),
    val db: AnimeDatabaseHelper = Injekt.get(),
    val preferences: PreferencesHelper = Injekt.get()
) : BasePresenter<GlobalAnimeSearchController>() {

    /**
     * Enabled sources.
     */
    val sources by lazy { getSourcesToQuery() }

    /**
     * Fetches the different sources by user settings.
     */
    private var fetchSourcesSubscription: Subscription? = null

    /**
     * Subject which fetches image of given anime.
     */
    private val fetchImageSubject = PublishSubject.create<Pair<List<Anime>, Source>>()

    /**
     * Subscription for fetching images of anime.
     */
    private var fetchImageSubscription: Subscription? = null

    private val extensionManager: ExtensionManager by injectLazy()

    private var extensionFilter: String? = null

    override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)

        extensionFilter = savedState?.getString(GlobalAnimeSearchPresenter::extensionFilter.name)
            ?: initialExtensionFilter

        // Perform a search with previous or initial state
        search(
            savedState?.getString(BrowseSourcePresenter::query.name)
                ?: initialQuery.orEmpty()
        )
    }

    override fun onDestroy() {
        fetchSourcesSubscription?.unsubscribe()
        fetchImageSubscription?.unsubscribe()
        super.onDestroy()
    }

    override fun onSave(state: Bundle) {
        state.putString(BrowseSourcePresenter::query.name, query)
        state.putString(GlobalAnimeSearchPresenter::extensionFilter.name, extensionFilter)
        super.onSave(state)
    }

    /**
     * Returns a list of enabled sources ordered by language and name, with pinned catalogues
     * prioritized.
     *
     * @return list containing enabled sources.
     */
    protected open fun getEnabledSources(): List<CatalogueSource> {
        val languages = preferences.enabledLanguages().get()
        val disabledSourceIds = preferences.disabledSources().get()
        val pinnedSourceIds = preferences.pinnedSources().get()

        return sourceManager.getCatalogueSources()
            .filter { it.lang in languages }
            .filterNot { it.id.toString() in disabledSourceIds }
            .sortedWith(compareBy({ it.id.toString() !in pinnedSourceIds }, { "${it.name.toLowerCase()} (${it.lang})" }))
    }

    private fun getSourcesToQuery(): List<CatalogueSource> {
        val filter = extensionFilter
        val enabledSources = getEnabledSources()
        var filteredSources: List<CatalogueSource>? = null

        if (!filter.isNullOrEmpty()) {
            filteredSources = extensionManager.installedExtensions
                .filter { it.pkgName == filter }
                .flatMap { it.sources }
                .filter { it in enabledSources }
                .filterIsInstance<CatalogueSource>()
        }

        if (filteredSources != null && filteredSources.isNotEmpty()) {
            return filteredSources
        }

        val onlyPinnedSources = preferences.searchPinnedSourcesOnly()
        val pinnedSourceIds = preferences.pinnedSources().get()

        return enabledSources
            .filter { if (onlyPinnedSources) it.id.toString() in pinnedSourceIds else true }
    }

    /**
     * Creates a catalogue search item
     */
    protected open fun createCatalogueSearchItem(source: CatalogueSource, results: List<GlobalAnimeSearchCardItem>?): GlobalAnimeSearchItem {
        return GlobalAnimeSearchItem(source, results)
    }

    /**
     * Initiates a search for anime per catalogue.
     *
     * @param query query on which to search.
     */
    fun search(query: String) {
        // Return if there's nothing to do
        if (this.query == query) return

        // Update query
        this.query = query

        // Create image fetch subscription
        initializeFetchImageSubscription()

        // Create items with the initial state
        val initialItems = sources.map { createCatalogueSearchItem(it, null) }
        var items = initialItems

        val pinnedSourceIds = preferences.pinnedSources().get()

        fetchSourcesSubscription?.unsubscribe()
        fetchSourcesSubscription = Observable.from(sources)
            .flatMap(
                { source ->
                    Observable.defer { source.fetchSearchAnime(1, query, FilterList()) }
                        .subscribeOn(Schedulers.io())
                        .onErrorReturn { AnimesPage(emptyList(), false) } // Ignore timeouts or other exceptions
                        .map { it.animes }
                        .map { list -> list.map { networkToLocalAnime(it, source.id) } } // Convert to local anime
                        .doOnNext { fetchImage(it, source) } // Load anime covers
                        .map { list -> createCatalogueSearchItem(source, list.map { GlobalAnimeSearchCardItem(it) }) }
                },
                5
            )
            .observeOn(AndroidSchedulers.mainThread())
            // Update matching source with the obtained results
            .map { result ->
                items
                    .map { item -> if (item.source == result.source) result else item }
                    .sortedWith(
                        compareBy(
                            // Bubble up sources that actually have results
                            { it.results.isNullOrEmpty() },
                            // Same as initial sort, i.e. pinned first then alphabetically
                            { it.source.id.toString() !in pinnedSourceIds },
                            { "${it.source.name.toLowerCase()} (${it.source.lang})" }
                        )
                    )
            }
            // Update current state
            .doOnNext { items = it }
            // Deliver initial state
            .startWith(initialItems)
            .subscribeLatestCache(
                { view, anime ->
                    view.setItems(anime)
                },
                { _, error ->
                    Timber.e(error)
                }
            )
    }

    /**
     * Initialize a list of anime.
     *
     * @param anime the list of anime to initialize.
     */
    private fun fetchImage(anime: List<Anime>, source: Source) {
        fetchImageSubject.onNext(Pair(anime, source))
    }

    /**
     * Subscribes to the initializer of anime details and updates the view if needed.
     */
    private fun initializeFetchImageSubscription() {
        fetchImageSubscription?.unsubscribe()
        fetchImageSubscription = fetchImageSubject.observeOn(Schedulers.io())
            .flatMap { (first, source) ->
                Observable.from(first)
                    .filter { it.thumbnail_url == null && !it.initialized }
                    .map { Pair(it, source) }
                    .concatMap { runAsObservable({ getAnimeDetails(it.first, it.second) }) }
                    .map { Pair(source as CatalogueSource, it) }
            }
            .onBackpressureBuffer()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { (source, anime) ->
                    @Suppress("DEPRECATION")
                    view?.onAnimeInitialized(source, anime)
                },
                { error ->
                    Timber.e(error)
                }
            )
    }

    /**
     * Initializes the given anime.
     *
     * @param anime the anime to initialize.
     * @return The initialized anime.
     */
    private suspend fun getAnimeDetails(anime: Anime, source: Source): Anime {
        val networkAnime = source.getAnimeDetails(anime.toAnimeInfo())
        anime.copyFrom(networkAnime.toSAnime())
        anime.initialized = true
        db.insertAnime(anime).executeAsBlocking()
        return anime
    }

    /**
     * Returns a anime from the database for the given anime from network. It creates a new entry
     * if the anime is not yet in the database.
     *
     * @param sAnime the anime from the source.
     * @return a anime from the database.
     */
    protected open fun networkToLocalAnime(sAnime: SAnime, sourceId: Long): Anime {
        var localAnime = db.getAnime(sAnime.url, sourceId).executeAsBlocking()
        if (localAnime == null) {
            val newAnime = Anime.create(sAnime.url, sAnime.title, sourceId)
            newAnime.copyFrom(sAnime)
            val result = db.insertAnime(newAnime).executeAsBlocking()
            newAnime.id = result.insertedId()
            localAnime = newAnime
        }
        return localAnime
    }
}