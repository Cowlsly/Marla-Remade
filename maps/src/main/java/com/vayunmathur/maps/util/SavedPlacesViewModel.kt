package com.vayunmathur.maps.util

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.maps.data.SavedPlace
import com.vayunmathur.maps.data.SavedPlaceStore
import com.vayunmathur.maps.data.SpecificFeature
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Exposes the user's saved places to the UI. P4 introduced the Home/Work
 * quick-access slots; P6 grows this into a full saved-places model backed by
 * [SavedPlaceStore]:
 *
 *  - [home] / [work] — single slots (unchanged public API so P4 wiring keeps
 *    working).
 *  - [saved] — a flat starred list built from the place sheet's Save action.
 *  - [lists] — named collections managed from the saved-places screen.
 *
 * All persistence lives in [SavedPlaceStore]; this class only adds the
 * `viewModelScope`, turns the store's flows into `StateFlow`s, and applies the
 * small list edits (add / remove / rename / list membership) against the latest
 * snapshot before writing it back.
 */
class SavedPlacesViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SavedPlaceStore(DataStoreUtils.getInstance(application))

    val home: StateFlow<SavedPlace?> =
        store.homeFlow().stateIn(viewModelScope, SharingStarted.Eagerly, store.homeInitial())
    val work: StateFlow<SavedPlace?> =
        store.workFlow().stateIn(viewModelScope, SharingStarted.Eagerly, store.workInitial())
    val saved: StateFlow<List<SavedPlace>> =
        store.savedFlow().stateIn(viewModelScope, SharingStarted.Eagerly, store.savedInitial())
    val lists: StateFlow<Map<String, List<SavedPlace>>> =
        store.listsFlow().stateIn(viewModelScope, SharingStarted.Eagerly, store.listsInitial())

    // --- Home / Work slots -------------------------------------------------

    fun setHome(feature: SpecificFeature.RoutableFeature) =
        launch { store.setHome(SavedPlace.from(feature)) }

    fun setWork(feature: SpecificFeature.RoutableFeature) =
        launch { store.setWork(SavedPlace.from(feature)) }

    fun clearHome() = launch { store.setHome(null) }
    fun clearWork() = launch { store.setWork(null) }

    // --- Flat saved (starred) list ----------------------------------------

    /** True if [feature] is already in the flat saved list. */
    fun isSaved(feature: SpecificFeature.RoutableFeature): Boolean =
        saved.value.any { it.matches(feature) }

    fun addSaved(feature: SpecificFeature.RoutableFeature) = launch {
        val place = SavedPlace.from(feature)
        if (saved.value.none { it.matches(feature) }) {
            store.setSaved(saved.value + place)
        }
    }

    fun removeSaved(place: SavedPlace) = launch { store.setSaved(saved.value - place) }

    fun renameSaved(place: SavedPlace, newName: String) = launch {
        val name = newName.trim()
        if (name.isEmpty()) return@launch
        store.setSaved(saved.value.map { if (it == place) it.copy(name = name) else it })
    }

    // --- Named lists -------------------------------------------------------

    fun createList(name: String) = launch {
        val listName = name.trim()
        if (listName.isEmpty() || lists.value.containsKey(listName)) return@launch
        store.setLists(lists.value + (listName to emptyList()))
    }

    fun deleteList(name: String) = launch { store.setLists(lists.value - name) }

    fun addToList(name: String, feature: SpecificFeature.RoutableFeature) = launch {
        val place = SavedPlace.from(feature)
        val current = lists.value[name].orEmpty()
        if (current.any { it.matches(feature) }) return@launch
        store.setLists(lists.value + (name to (current + place)))
    }

    fun addToList(name: String, place: SavedPlace) = launch {
        val current = lists.value[name].orEmpty()
        if (place in current) return@launch
        store.setLists(lists.value + (name to (current + place)))
    }

    fun removeFromList(name: String, place: SavedPlace) = launch {
        val current = lists.value[name] ?: return@launch
        store.setLists(lists.value + (name to (current - place)))
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
