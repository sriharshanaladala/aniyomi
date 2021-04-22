package eu.kanade.tachiyomi.ui.watcher.viewer.webtoon

import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.ui.watcher.viewer.ViewerConfig
import eu.kanade.tachiyomi.ui.watcher.viewer.ViewerNavigation
import eu.kanade.tachiyomi.ui.watcher.viewer.navigation.EdgeNavigation
import eu.kanade.tachiyomi.ui.watcher.viewer.navigation.KindlishNavigation
import eu.kanade.tachiyomi.ui.watcher.viewer.navigation.LNavigation
import eu.kanade.tachiyomi.ui.watcher.viewer.navigation.RightAndLeftNavigation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Configuration used by webtoon viewers.
 */
class WebtoonConfig(
    scope: CoroutineScope,
    preferences: PreferencesHelper = Injekt.get()
) : ViewerConfig(preferences, scope) {

    var imageCropBorders = false
        private set

    var sidePadding = 0
        private set

    init {
        preferences.cropBordersWebtoon()
            .register({ imageCropBorders = it }, { imagePropertyChangedListener?.invoke() })

        preferences.webtoonSidePadding()
            .register({ sidePadding = it }, { imagePropertyChangedListener?.invoke() })

        preferences.navigationModeWebtoon()
            .register({ navigationMode = it }, { updateNavigation(it) })

        preferences.webtoonNavInverted()
            .register({ tappingInverted = it }, { navigator.invertMode = it })
        preferences.webtoonNavInverted().asFlow()
            .drop(1)
            .onEach { navigationModeChangedListener?.invoke() }
            .launchIn(scope)

        preferences.dualPageSplitWebtoon()
            .register({ dualPageSplit = it }, { imagePropertyChangedListener?.invoke() })

        preferences.dualPageInvertWebtoon()
            .register({ dualPageInvert = it }, { imagePropertyChangedListener?.invoke() })
    }

    override var navigator: ViewerNavigation = defaultNavigation()
        set(value) {
            field = value.also { it.invertMode = tappingInverted }
        }

    override fun defaultNavigation(): ViewerNavigation {
        return LNavigation()
    }

    override fun updateNavigation(navigationMode: Int) {
        this.navigator = when (navigationMode) {
            0 -> defaultNavigation()
            1 -> LNavigation()
            2 -> KindlishNavigation()
            3 -> EdgeNavigation()
            4 -> RightAndLeftNavigation()
            else -> defaultNavigation()
        }
        navigationModeChangedListener?.invoke()
    }
}