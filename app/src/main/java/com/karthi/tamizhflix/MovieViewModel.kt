package com.karthi.tamizhflix

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.karthi.tamizhflix.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

class MovieViewModel : ViewModel() {
    val transport = HttpPageFetcher()
    private val repository = CatalogueRepository(transport)
    var query by mutableStateOf("")
    var year by mutableStateOf("")
    var source by mutableStateOf(Source.TAMIL)
        private set
    var results by mutableStateOf<List<Link>>(emptyList())
        private set
    var searched by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var warning by mutableStateOf<String?>(null)
        private set
    var selected by mutableStateOf<Link?>(null)
        private set
    var resolution by mutableStateOf<Resolution?>(null)
        private set
    var resolving by mutableStateOf(false)
        private set
    var resolverError by mutableStateOf<String?>(null)
        private set
    private var searchJob: Job? = null
    private var resolveJob: Job? = null
    private var lastLink: Link? = null
    private val history = mutableListOf<Resolution>()
    var canGoBack by mutableStateOf(false)
        private set

    fun search() {
        searchJob?.cancel()
        results = emptyList()
        searched = true
        error = null
        warning = null
        loading = true
        val title = query.trim()
        val releaseYear = year.trim()
        val provider = source
        searchJob = viewModelScope.launch {
            try {
                val result = repository.search(provider, title, releaseYear)
                results = result.movies
                warning = result.warning
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = message(e) }
            finally { if (isActive) loading = false }
        }
    }
    fun changeSource(value: Source) {
        searchJob?.cancel()
        loading = false
        source = value
        results = emptyList()
        searched = false
        error = null
        warning = null
    }
    fun open(movie: Link) { close(); selected = movie; follow(movie) }
    fun follow(link: Link, remember: Boolean = true) {
        resolveJob?.cancel()
        if (remember) resolution?.let { history.add(it) }
        canGoBack = history.isNotEmpty()
        lastLink = link
        resolverError = null
        resolving = true
        resolution = null
        resolveJob = viewModelScope.launch {
            try { resolution = repository.resolve(link) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { resolverError = message(e) }
            finally { if (isActive) resolving = false }
        }
    }
    fun retry() { lastLink?.let { follow(it, false) } }
    fun back() {
        resolveJob?.cancel()
        resolving = false
        resolverError = null
        resolution = history.removeLastOrNull()
        canGoBack = history.isNotEmpty()
    }
    fun close() {
        resolveJob?.cancel()
        selected = null
        resolution = null
        resolverError = null
        resolving = false
        history.clear()
        canGoBack = false
    }
    private fun message(e: Exception) = when (e) {
        is java.net.UnknownHostException -> "Cannot reach the source. Check your connection and try again."
        is java.net.SocketTimeoutException -> "The source took too long to respond. Try again."
        else -> e.message ?: "Something went wrong. Try another release."
    }
}
