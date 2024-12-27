package eu.kanade.tachiyomi.data.download

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.model.getComicInfo
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.library.LibraryUpdateNotifier
import eu.kanade.tachiyomi.data.notification.NotificationHandler
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.DiskUtil.NOMEDIA_FILE
import eu.kanade.tachiyomi.util.storage.saveTo
import eu.kanade.translation.data.TranslationProvider
import eu.kanade.translation.model.Translation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import logcat.LogPriority
import mihon.core.archive.ZipWriter
import nl.adaptivity.xmlutil.serialization.XML
import okhttp3.Response
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNow
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.Locale

class ChapterTranslator(
    private val context: Context,
    private val provider: TranslationProvider,
    private val sourceManager: SourceManager = Injekt.get(),
    private val translationPreferences: TranslationPreferences = Injekt.get(),
) {

    private val _queueState = MutableStateFlow<List<Translation>>(emptyList())
    val queueState = _queueState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var translationJob: Job? = null

    val isRunning: Boolean
        get() = translationJob?.isActive == true

    @Volatile
    var isPaused: Boolean = false

    init {
        //TODO initialize actual translator
    }

    fun start(): Boolean {
        if (isRunning || queueState.value.isEmpty()) {
            return false
        }

        val pending = queueState.value.filter { it.status != Translation.State.TRANSLATED }
        pending.forEach { if (it.status != Translation.State.QUEUE) it.status = Translation.State.QUEUE }
        isPaused = false
        launchTranslatorJob()
        return pending.isNotEmpty()
    }

    fun stop(reason: String? = null) {
        cancelTranslatorJob()
        queueState.value
            .filter { it.status == Translation.State.TRANSLATING }
            .forEach { it.status = Translation.State.ERROR }
        if (reason != null) return
        isPaused = false
    }

    fun pause() {
        cancelTranslatorJob()
        queueState.value
            .filter { it.status == Translation.State.TRANSLATING }
            .forEach { it.status = Translation.State.QUEUE }
        isPaused = true
    }

    fun clearQueue() {
        cancelTranslatorJob()
        internalClearQueue()
    }

    private fun launchTranslatorJob() {
        if (isRunning) return

        translationJob = scope.launch {
            val activeTranslationFlow = queueState.transformLatest { queue ->
                while (true) {
                    val activeTranslations = queue.asSequence()
                        .filter { it.status.value <= Translation.State.TRANSLATING.value }
                        .groupBy { it.source }
                        .toList()
                        .take(5)
                        .map { (_, translations) -> translations.first() }
                    emit(activeTranslations)

                    if (activeTranslations.isEmpty()) break
                    val activeTranslationsErroredFlow =
                        combine(activeTranslations.map(Translation::statusFlow)) { states ->
                            states.contains(Translation.State.ERROR)
                        }.filter { it }
                    activeTranslationsErroredFlow.first()
                }
            }.distinctUntilChanged()
            supervisorScope {
                val translationJobs = mutableMapOf<Translation, Job>()

                activeTranslationFlow.collectLatest { activeTranslations ->
                    val translationJobsToStop = translationJobs.filter { it.key !in activeTranslations }
                    translationJobsToStop.forEach { (download, job) ->
                        job.cancel()
                        translationJobs.remove(download)
                    }

                    val translationsToStart = activeTranslations.filter { it !in translationJobs }
                    translationsToStart.forEach { translation ->
                        translationJobs[translation] = launchTranslationJob(translation)
                    }
                }
            }
        }
    }

    private fun CoroutineScope.launchTranslationJob(translation: Translation) = launchIO {
        try {
            translateChapter(translation)
            if (translation.status == Translation.State.TRANSLATED) {
                removeFromQueue(translation)
            }
            if (areAllTranslationsFinished()) {
                stop()
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e)
            stop()
        }
    }

    private fun cancelTranslatorJob() {
        translationJob?.cancel()
        translationJob = null
    }

    fun queueChapter(manga: Manga, chapter: Chapter) {
        val source = sourceManager.get(manga.source) as? HttpSource ?: return
        if (provider.findTranslationFile(chapter.name, chapter.scanlator, manga.title, source) != null) return
        if (queueState.value.any { it.chapter.id == chapter.id }) return
        val translation = Translation(source, manga, chapter);
        addToQueue(translation);
    }
    //TODO
    private suspend fun translateChapter(translation: Translation) {
        logcat{"TRANSLATING CHAPTER"}
        val mangaDir = provider.getMangaDir(translation.manga.title, translation.source)
        val filename = provider.getTranslationFileName(translation.chapter.name, translation.chapter.scanlator)
        mangaDir.createFile(filename)
        translation.status = Translation.State.TRANSLATED
        logcat{"TRANSLATED CHAPTER"}
//        val mangaDir = provider.getMangaDir(download.manga.title, download.source)
//
//        val availSpace = DiskUtil.getAvailableStorageSpace(mangaDir)
//        if (availSpace != -1L && availSpace < MIN_DISK_SPACE) {
//            download.status = Download.State.ERROR
//            notifier.onError(
//                context.stringResource(MR.strings.download_insufficient_space),
//                download.chapter.name,
//                download.manga.title,
//                download.manga.id,
//            )
//            return
//        }
//
//        val chapterDirname = provider.getChapterDirName(download.chapter.name, download.chapter.scanlator)
//        val tmpDir = mangaDir.createDirectory(chapterDirname + TMP_DIR_SUFFIX)!!
//
//        try {
//            // If the page list already exists, start from the file
//            val pageList = download.pages ?: run {
//                // Otherwise, pull page list from network and add them to download object
//                val pages = download.source.getPageList(download.chapter.toSChapter())
//
//                if (pages.isEmpty()) {
//                    throw Exception(context.stringResource(MR.strings.page_list_empty_error))
//                }
//                // Don't trust index from source
//                val reIndexedPages = pages.mapIndexed { index, page -> Page(index, page.url, page.imageUrl, page.uri) }
//                download.pages = reIndexedPages
//                reIndexedPages
//            }
//
//            // Delete all temporary (unfinished) files
//            tmpDir.listFiles()
//                ?.filter { it.extension == "tmp" }
//                ?.forEach { it.delete() }
//
//            download.status = Download.State.DOWNLOADING
//
//            // Start downloading images, consider we can have downloaded images already
//            // Concurrently do 2 pages at a time
//            pageList.asFlow()
//                .flatMapMerge(concurrency = 2) { page ->
//                    flow {
//                        // Fetch image URL if necessary
//                        if (page.imageUrl.isNullOrEmpty()) {
//                            page.status = Page.State.LOAD_PAGE
//                            try {
//                                page.imageUrl = download.source.getImageUrl(page)
//                            } catch (e: Throwable) {
//                                page.status = Page.State.ERROR
//                            }
//                        }
//
//                        withIOContext { getOrDownloadImage(page, download, tmpDir) }
//                        emit(page)
//                    }.flowOn(Dispatchers.IO)
//                }
//                .collect {
//                    // Do when page is downloaded.
//                    notifier.onProgressChange(download)
//                }
//
//            // Do after download completes
//
//            if (!isDownloadSuccessful(download, tmpDir)) {
//                download.status = Download.State.ERROR
//                return
//            }
//
//            createComicInfoFile(
//                tmpDir,
//                download.manga,
//                download.chapter,
//                download.source,
//            )
//
//            // Only rename the directory if it's downloaded
//            if (downloadPreferences.saveChaptersAsCBZ().get()) {
//                archiveChapter(mangaDir, chapterDirname, tmpDir)
//            } else {
//                tmpDir.renameTo(chapterDirname)
//            }
//            cache.addChapter(chapterDirname, mangaDir, download.manga)
//
//            DiskUtil.createNoMediaFile(tmpDir, context)
//
//            download.status = Download.State.DOWNLOADED
//        } catch (error: Throwable) {
//            if (error is CancellationException) throw error
//            // If the page list threw, it will resume here
//            logcat(LogPriority.ERROR, error)
//            download.status = Download.State.ERROR
//            notifier.onError(error.message, download.chapter.name, download.manga.title, download.manga.id)
//        }
    }


    private fun areAllTranslationsFinished(): Boolean {
        return queueState.value.none { it.status.value <= Translation.State.TRANSLATING.value }
    }

    private fun addToQueue(translation: Translation) {
        translation.status= Translation.State.QUEUE;
        _queueState.update {
            it + translation
        }
    }

    private fun removeFromQueue(translation: Translation) {
        _queueState.update {
           if (translation.status == Translation.State.TRANSLATING || translation.status == Translation.State.QUEUE) {
            translation.status = Translation.State.NOT_TRANSLATED
            }
            it - translation
        }
    }

    private inline fun removeFromQueueIf(predicate: (Translation) -> Boolean) {
        _queueState.update { queue ->
            val translations = queue.filter { predicate(it) }
            translations.forEach { translation ->
                if (translation.status == Translation.State.TRANSLATING || translation.status == Translation.State.QUEUE) {
                    translation.status = Translation.State.NOT_TRANSLATED
                }
            }
            queue - translations
        }
    }

    fun removeFromQueue(chapter: Chapter) {
        removeFromQueueIf { it.chapter.id == chapter.id }
    }

    fun removeFromQueue(manga: Manga) {
        removeFromQueueIf { it.manga.id == manga.id }
    }

    private fun internalClearQueue() {
        _queueState.update {
            it.forEach { translation ->
                if (translation.status == Translation.State.TRANSLATING || translation.status == Translation.State.QUEUE) {
                    translation.status = Translation.State.NOT_TRANSLATED
                }
            }
            emptyList()
        }
    }
}
