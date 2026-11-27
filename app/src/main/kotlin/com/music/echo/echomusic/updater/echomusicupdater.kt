package echo.music.iad1tya.echomusic.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Observer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.navigation.NavHostController
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import coil3.compose.AsyncImage
import echo.music.iad1tya.BuildConfig
import echo.music.iad1tya.R
import echo.music.iad1tya.echomusic.updater.downloadmanager.DownloadNotificationManager
import echo.music.iad1tya.echomusic.updater.downloadmanager.UpdateDownloadWorker
import echo.music.iad1tya.ui.component.AnimatedActionButton
import echo.music.iad1tya.ui.component.ChangelogItem
import echo.music.iad1tya.ui.component.DefaultDialog
import echo.music.iad1tya.ui.component.ErrorSnackbar
import echo.music.iad1tya.ui.component.ExpressiveIconButton
import echo.music.iad1tya.ui.component.detachedItemShape
import echo.music.iad1tya.ui.component.endItemShape
import echo.music.iad1tya.ui.component.leadingItemShape
import echo.music.iad1tya.ui.component.middleItemShape
import echo.music.iad1tya.ui.component.parseMarkdown
import echo.music.iad1tya.ui.utils.parseMarkdownToSections
import echo.music.iad1tya.ui.utils.parseSimpleMarkdown
import echo.music.iad1tya.ui.utils.safeOpenUri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// Automatically set to your fork's GitHub repository (owner/repo) and release tag by the
// sync-upstream-and-build GitHub Actions pipeline before compiling the APK.
// For local builds, you may replace UPDATE_GITHUB_REPO with "<your-username>/<your-repo>".
const val UPDATE_GITHUB_REPO = "EchoMusicApp/Echo-Music"
const val INSTALLED_RELEASE_TAG = ""
const val GITHUB_RELEASES_URL = "https://github.com/$UPDATE_GITHUB_REPO/releases"
const val GITHUB_RELEASES_API_URL = "https://api.github.com/repos/$UPDATE_GITHUB_REPO/releases"

fun isReleaseNewerThanInstalled(releaseTag: String): Boolean {
  val cleanTag = releaseTag.trim()
  if (cleanTag.isEmpty()) return false
  if (INSTALLED_RELEASE_TAG.isNotBlank()) {
    return cleanTag != INSTALLED_RELEASE_TAG.trim()
  }
  val currentClean = BuildConfig.VERSION_NAME.removePrefix("b").removePrefix("v").trim()
  val targetClean = cleanTag.removePrefix("b").removePrefix("v").trim()
  return currentClean != targetClean
}

fun fetchLatestGitHubRelease(): JSONObject? {
  val releasesJson =
    openTimedStream("$GITHUB_RELEASES_API_URL?per_page=10")
      .bufferedReader()
      .use { it.readText() }
  val releases = JSONArray(releasesJson)
  var firstNonDraft: JSONObject? = null
  for (i in 0 until releases.length()) {
    val release = releases.getJSONObject(i)
    if (release.optBoolean("draft", false)) continue
    if (firstNonDraft == null) {
      firstNonDraft = release
    }
    val isPrerelease = release.optBoolean("prerelease", false)
    if (BuildConfig.DEBUG || !isPrerelease) {
      return release
    }
  }
  return firstNonDraft
}

data class CachedUpdateAsset(
  val version: String,
  val apkUrl: String?,
  val fileSize: String,
)

@Volatile
var latestCachedUpdateAsset: CachedUpdateAsset? = null
  private set

fun enqueueApkUpdateDownload(
  context: Context,
  apkUrl: String,
  version: String,
  fileSize: String,
) {
  DownloadNotificationManager.initialize(context)
  val constraints =
    Constraints.Builder()
      .setRequiredNetworkType(NetworkType.CONNECTED)
      .build()

  val downloadRequest =
    OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
      .setInputData(
        workDataOf(
          "apk_url" to apkUrl,
          "version" to version,
          "file_size" to fileSize,
        ),
      )
      .setConstraints(constraints)
      .setBackoffCriteria(
        BackoffPolicy.EXPONENTIAL,
        10,
        java.util.concurrent.TimeUnit.SECONDS,
      )
      .addTag("update_download")
      .addTag("version:$version")
      .build()

  WorkManager.getInstance(context)
    .enqueueUniqueWork(
      "update_download",
      ExistingWorkPolicy.REPLACE,
      downloadRequest,
    )
}

fun installDownloadedApk(
  context: Context,
  file: File,
  fallbackUrl: String?,
  onInstallError: () -> Unit = {},
) {
  try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      if (!context.packageManager.canRequestPackageInstalls()) {
        val intent =
          Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .apply {
              data = Uri.parse("package:${context.packageName}")
              addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(intent)
        return
      }
    }
    val uri =
      FileProvider.getUriForFile(
        context,
        "${context.packageName}.FileProvider",
        file,
      )
    val installIntent =
      Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    ContextCompat.startActivity(context, installIntent, null)
  } catch (e: SecurityException) {
    Log.w(
      "UpdateInstall",
      "Install permission not declared in this build; opening release download",
      e,
    )
    context.safeOpenUri(fallbackUrl ?: GITHUB_RELEASES_URL)
  } catch (e: Exception) {
    Log.e("UpdateInstall", "Failed to launch APK installer", e)
    onInstallError()
  }
}

data class ChangelogSection(val title: String, val items: List<String>)

sealed class EchoUpdateStatus {
  object Idle : EchoUpdateStatus()

  object Checking : EchoUpdateStatus()

  data class Available(
    val version: String,
    val changelog: List<ChangelogSection>,
    val size: String,
    val releaseDate: String,
    val description: String?,
    val imageUrl: String?,
    val apkUrl: String?,
  ) : EchoUpdateStatus()

  data class NoUpdate(val version: String) : EchoUpdateStatus()

  data class Error(val message: String) : EchoUpdateStatus()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpdateScreen(navController: NavHostController) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var status by remember {
    mutableStateOf<EchoUpdateStatus>(EchoUpdateStatus.NoUpdate(BuildConfig.VERSION_NAME))
  }
  var isDownloading by remember { mutableStateOf(false) }
  var downloadProgress by remember { mutableStateOf(0f) }
  var isDownloadComplete by remember { mutableStateOf(false) }
  var downloadedFile by remember { mutableStateOf<File?>(null) }
  val snackbarHostState = remember { SnackbarHostState() }

  val currentVersion = BuildConfig.VERSION_NAME
  val autoUpdateCheckEnabled = getAutoUpdateCheckSetting(context)

  LaunchedEffect(Unit) { DownloadNotificationManager.initialize(context) }

  DisposableEffect(Unit) {
    val liveData =
      WorkManager.getInstance(context).getWorkInfosForUniqueWorkLiveData("update_download")
    val observer =
      Observer<List<WorkInfo>> { workInfos ->
        val workInfo = workInfos.firstOrNull() ?: return@Observer

        when (workInfo.state) {
          WorkInfo.State.RUNNING -> {
            isDownloading = true
            downloadProgress = workInfo.progress.getFloat("progress", 0f)
          }
          WorkInfo.State.SUCCEEDED -> {
            isDownloading = false
            isDownloadComplete = true
            val filePath = workInfo.outputData.getString("file_path")
            if (filePath != null) {
              downloadedFile = File(filePath)
            }
          }
          WorkInfo.State.FAILED -> {
            isDownloading = false
            scope.launch {
              snackbarHostState.showSnackbar(context.getString(R.string.download_failed))
            }
          }
          WorkInfo.State.CANCELLED -> {
            isDownloading = false
            downloadProgress = 0f
          }
          else -> {}
        }
      }
    liveData.observeForever(observer)
    onDispose { liveData.removeObserver(observer) }
  }

  LaunchedEffect(isDownloadComplete, downloadedFile) {
    if (isDownloadComplete && downloadedFile != null) {
      if (!downloadedFile!!.exists()) {
        isDownloadComplete = false
        downloadedFile = null
        downloadProgress = 0f
      }
    }
  }

  fun triggerUpdateCheck() {
    status = EchoUpdateStatus.Checking
    scope.launch {
      delay(1000L)
      checkForUpdate(
        context = context,
        onSuccess = { tag, isAvailable, changelog, size, date, description, imageUrl, apkUrl ->
          saveLastCheckedTime(
            context,
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMMM yyyy, h:mm a")),
          )
          saveUpdateAvailableState(context, isAvailable)
          status =
            if (isAvailable) {
              EchoUpdateStatus.Available(
                version = tag,
                changelog = changelog,
                size = size,
                releaseDate = date,
                description = description,
                imageUrl = imageUrl,
                apkUrl = apkUrl,
              )
            } else {
              EchoUpdateStatus.NoUpdate(tag)
            }
        },
        onError = {
          status = EchoUpdateStatus.Error(context.getString(R.string.cant_check_updates))
        },
      )
    }
  }

  LaunchedEffect(Unit) {
    triggerUpdateCheck()
  }

  val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

  Scaffold(
    topBar = {
      LargeTopAppBar(
        title = {
          val titleText =
            if (status is EchoUpdateStatus.Available) {
              buildAnnotatedString {
                append(stringResource(R.string.new_update) + " ")
                withStyle(
                  SpanStyle(
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                  ),
                ) {
                  append((status as EchoUpdateStatus.Available).version)
                }
              }
            } else {
              AnnotatedString(stringResource(R.string.settings_check_updates_title))
            }
          Text(text = titleText, maxLines = 1)
        },
        navigationIcon = {
          Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp)) {
            ExpressiveIconButton(
              onClick = { navController.navigateUp() },
              painter = painterResource(R.drawable.arrow_back),
              contentDescription = stringResource(R.string.cancel),
              containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
              contentColor = MaterialTheme.colorScheme.onSurface,
            )
          }
        },
        scrollBehavior = scrollBehavior,
        colors =
          TopAppBarDefaults.largeTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
          ),
      )
    },
    bottomBar = {
      Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
        Column(
          modifier =
            Modifier.fillMaxWidth()
              .padding(WindowInsets.navigationBars.asPaddingValues())
              .padding(horizontal = 24.dp, vertical = 16.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          when (val currentStatus = status) {
            is EchoUpdateStatus.Idle,
            is EchoUpdateStatus.Checking,
            is EchoUpdateStatus.NoUpdate,
            is EchoUpdateStatus.Error -> {
              AnimatedActionButton(
                text = stringResource(R.string.check_for_update),
                onClick = { triggerUpdateCheck() },
                enabled = currentStatus !is EchoUpdateStatus.Checking && !isDownloading,
                modifier = Modifier.fillMaxWidth(),
              )
            }
            is EchoUpdateStatus.Available -> {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                AnimatedActionButton(
                  text = stringResource(R.string.later),
                  onClick = { navController.navigateUp() },
                  modifier = Modifier.weight(1f),
                  isOutlined = true,
                  enabled = !isDownloading,
                )
                AnimatedActionButton(
                  text =
                    if (isDownloading) "${(downloadProgress * 100).toInt()}%"
                    else if (isDownloadComplete) stringResource(R.string.install)
                    else stringResource(R.string.update_available),
                  onClick = {
                    if (isDownloadComplete) {
                      val file = downloadedFile
                      if (file == null || !file.exists()) {
                        isDownloadComplete = false
                        downloadedFile = null
                        downloadProgress = 0f
                        return@AnimatedActionButton
                      }
                      file.let { f ->
                        installDownloadedApk(
                          context = context,
                          file = f,
                          fallbackUrl = currentStatus.apkUrl,
                          onInstallError = {
                            scope.launch {
                              snackbarHostState.showSnackbar(
                                context.getString(R.string.download_failed),
                              )
                            }
                          },
                        )
                      }
                    } else {
                      val urlToDownload = currentStatus.apkUrl
                      if (urlToDownload.isNullOrBlank()) {
                        context.safeOpenUri(GITHUB_RELEASES_URL)
                      } else {
                        enqueueApkUpdateDownload(
                          context = context,
                          apkUrl = urlToDownload,
                          version = currentStatus.version,
                          fileSize = currentStatus.size,
                        )
                        isDownloading = true
                      }
                    }
                  },
                  modifier = Modifier.weight(1f),
                  enabled = !isDownloading || isDownloadComplete,
                )
              }
            }
          }
        }
      }
    },
    snackbarHost = { ErrorSnackbar(hostState = snackbarHostState) },
    containerColor = MaterialTheme.colorScheme.background,
    modifier =
      Modifier.widthIn(max = 700.dp)
        .fillMaxSize()
        .nestedScroll(scrollBehavior.nestedScrollConnection),
  ) { padding ->
    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        item {
          val contentModifier =
            if (status is EchoUpdateStatus.Available) {
              Modifier.fillMaxWidth()
            } else {
              Modifier.fillParentMaxSize()
            }

          Box(modifier = contentModifier, contentAlignment = Alignment.Center) {
            when (val currentStatus = status) {
              is EchoUpdateStatus.Checking -> {
                androidx.compose.material3.ContainedLoadingIndicator(
                  modifier = Modifier.size(64.dp),
                )
              }
              is EchoUpdateStatus.NoUpdate -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                  Icon(
                    painter = painterResource(R.drawable.deployed_app_update),
                    contentDescription = null,
                    modifier = Modifier.size(120.dp),
                    tint = MaterialTheme.colorScheme.primary,
                  )
                  Spacer(modifier = Modifier.height(24.dp))
                  Text(
                    text = stringResource(R.string.on_latest_version),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                  )
                  Spacer(modifier = Modifier.height(8.dp))
                  Text(
                    text = stringResource(R.string.current_version_v, currentStatus.version),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                  )
                }
              }
              is EchoUpdateStatus.Error -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                  Icon(
                    painter = painterResource(R.drawable.error),
                    contentDescription = null,
                    modifier = Modifier.size(120.dp),
                    tint = MaterialTheme.colorScheme.error,
                  )
                  Spacer(modifier = Modifier.height(24.dp))
                  Text(
                    text = currentStatus.message,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                  )
                }
              }
              is EchoUpdateStatus.Available -> {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                  Spacer(modifier = Modifier.height(12.dp))
                  Text(
                    text = stringResource(R.string.release_date_v, currentStatus.releaseDate),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                  if (currentStatus.size.isNotBlank()) {
                    Text(
                      text = stringResource(R.string.update_size_v, currentStatus.size),
                      style = MaterialTheme.typography.bodyMedium,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                  Spacer(modifier = Modifier.height(24.dp))
                  if (!currentStatus.imageUrl.isNullOrBlank()) {
                    AsyncImage(
                      model = currentStatus.imageUrl,
                      contentDescription = null,
                      modifier =
                        Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(24.dp)),
                      contentScale = ContentScale.Crop,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                  }
                  if (!currentStatus.description.isNullOrBlank()) {
                    val annotatedText = currentStatus.description.parseMarkdown()

                    ClickableText(
                      text = annotatedText,
                      onClick = { offset ->
                        annotatedText
                          .getStringAnnotations("URL", offset, offset)
                          .firstOrNull()
                          ?.let {
                            context.safeOpenUri(it.item)
                          }
                      },
                      style =
                        MaterialTheme.typography.bodyMedium.copy(
                          color = MaterialTheme.colorScheme.onSurfaceVariant,
                          lineHeight = 20.sp,
                        ),
                      modifier = Modifier.padding(bottom = 24.dp),
                    )
                  }

                  currentStatus.changelog.forEach { section ->
                    Text(
                      text = section.title,
                      style = MaterialTheme.typography.titleMedium,
                      fontWeight = FontWeight.Bold,
                      color = MaterialTheme.colorScheme.onBackground,
                      modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                    )
                    section.items.forEachIndexed { index, item ->
                      val shape =
                        when {
                          section.items.size == 1 -> detachedItemShape()
                          index == 0 -> leadingItemShape()
                          index == section.items.size - 1 -> endItemShape()
                          else -> middleItemShape()
                        }
                      ChangelogItem(text = item, shape = shape)
                      if (index != section.items.size - 1) {
                        Spacer(modifier = Modifier.height(2.dp))
                      }
                    }
                  }

                  if (currentStatus.changelog.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(24.dp))
                  }

                  if (isDownloading) {
                    if (downloadProgress > 0f) {
                      androidx.compose.material3.LinearProgressIndicator(
                        progress = downloadProgress,
                        modifier =
                          Modifier.fillMaxWidth()
                            .height(8.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                      )
                    } else {
                      androidx.compose.material3.LinearProgressIndicator(
                        modifier =
                          Modifier.fillMaxWidth()
                            .height(8.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                      )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                  }
                }
              }
              else -> {}
            }
          }
        }
      }
    }
  }
}

const val PREFS_NAME = "settings"
const val KEY_AUTO_UPDATE_CHECK = "auto_update_check"
const val KEY_LAST_CHECKED_TIME = "last_checked_time"
const val KEY_BETA_UPDATES = "beta_updates"
const val KEY_UPDATE_AVAILABLE = "update_available"

fun getUpdateAvailableState(context: Context): Boolean {
  val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  return sharedPrefs.getBoolean(KEY_UPDATE_AVAILABLE, false)
}

fun saveUpdateAvailableState(context: Context, available: Boolean) {
  val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  sharedPrefs.edit().putBoolean(KEY_UPDATE_AVAILABLE, available).apply()
}

fun getAutoUpdateCheckSetting(context: Context): Boolean {
  val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  return sharedPrefs.getBoolean(KEY_AUTO_UPDATE_CHECK, true)
}

fun saveAutoUpdateCheckSetting(context: Context, enabled: Boolean) {
  val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  sharedPrefs.edit().putBoolean(KEY_AUTO_UPDATE_CHECK, enabled).apply()
}

const val KEY_UPDATE_NOTIFICATIONS = "update_notifications"

fun getUpdateNotificationsSetting(context: Context): Boolean {
  val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  return sharedPrefs.getBoolean(KEY_UPDATE_NOTIFICATIONS, true)
}

fun saveUpdateNotificationsSetting(context: Context, enabled: Boolean) {
  val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  sharedPrefs.edit().putBoolean(KEY_UPDATE_NOTIFICATIONS, enabled).apply()
}

fun saveLastCheckedTime(context: Context, timestamp: String) {
  val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  sharedPrefs.edit().putString(KEY_LAST_CHECKED_TIME, timestamp).apply()
}

fun getLastCheckedTime(context: Context): String {
  val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  return sharedPrefs.getString(KEY_LAST_CHECKED_TIME, "") ?: ""
}

fun getBetaUpdatesSetting(context: Context): Boolean {
  val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  return sharedPrefs.getBoolean(KEY_BETA_UPDATES, false)
}

fun saveBetaUpdatesSetting(context: Context, enabled: Boolean) {
  val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  sharedPrefs.edit().putBoolean(KEY_BETA_UPDATES, enabled).apply()
}

private fun formatGitHubDate(githubDate: String): String =
  try {
    val githubFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
    val displayFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy, h:mm a")
    val dateTime = LocalDateTime.parse(githubDate, githubFormatter)
    dateTime.format(displayFormatter)
  } catch (e: Exception) {
    githubDate
  }

fun isNewerVersion(latestVersion: String, currentVersion: String): Boolean {
  val latestVersionClean = latestVersion.removePrefix("b").removePrefix("v")
  val currentVersionClean = currentVersion.removePrefix("b").removePrefix("v")

  val latestParts = latestVersionClean.split(".").map { it.toIntOrNull() ?: 0 }
  val currentParts = currentVersionClean.split(".").map { it.toIntOrNull() ?: 0 }

  for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
    val latest = latestParts.getOrElse(i) { 0 }
    val current = currentParts.getOrElse(i) { 0 }
    when {
      latest > current -> return true
      latest < current -> return false
    }
  }

  if (latestVersionClean == currentVersionClean) {
    val latestIsBeta = latestVersion.startsWith("b")
    val currentIsBeta = currentVersion.startsWith("b")

    if (currentIsBeta && !latestIsBeta) return true
  }

  return false
}

suspend fun checkForUpdate(
  context: Context,
  onSuccess:
    (
      tag: String,
      isAvailable: Boolean,
      changelog: List<ChangelogSection>,
      size: String,
      date: String,
      description: String?,
      imageUrl: String?,
      apkUrl: String?,
    ) -> Unit,
  onError: () -> Unit,
) {
  withContext(Dispatchers.IO) {
    try {
      val targetRelease =
        fetchLatestGitHubRelease()
          ?: run {
            withContext(Dispatchers.Main) {
              onSuccess(
                INSTALLED_RELEASE_TAG.ifBlank { BuildConfig.VERSION_NAME },
                false,
                emptyList(),
                "",
                "",
                null,
                null,
                null,
              )
            }
            return@withContext
          }

      val currentVersion = INSTALLED_RELEASE_TAG.ifBlank { BuildConfig.VERSION_NAME }
      val targetTagName = targetRelease.optString("tag_name").trim()
      val shouldShow = isReleaseNewerThanInstalled(targetTagName)

      if (shouldShow) {
        val tagWithPrefix = targetTagName
        val displayTag = tagWithPrefix

        val changelogList = mutableListOf<ChangelogSection>()
        var description: String? = null
        var imageUrl: String? = null
        try {
          val changelogJson =
            openTimedStream(
                "$GITHUB_RELEASES_URL/download/$tagWithPrefix/changelog.json",
              )
              .bufferedReader()
              .use { it.readText() }
          val changelogData = JSONObject(changelogJson)

          description = changelogData.optString("description").takeIf { it.isNotEmpty() }
          imageUrl = changelogData.optString("image").takeIf { it.isNotEmpty() }

          val changelogArray = changelogData.getJSONArray("changelog")
          for (j in 0 until changelogArray.length()) {
            val sectionObj = changelogArray.getJSONObject(j)
            val title = sectionObj.getString("title")
            val itemsArray = sectionObj.getJSONArray("items")
            val itemsList = mutableListOf<String>()
            for (k in 0 until itemsArray.length()) {
              itemsList.add(itemsArray.getString(k))
            }
            changelogList.add(ChangelogSection(title, itemsList))
          }
        } catch (e: Exception) {
          var body =
            targetRelease.optString("body", context.getString(R.string.no_changelog_available))

          val imageRegex = Regex("!\\[(.*?)\\]\\((.*?)\\)")
          val match = imageRegex.find(body)
          if (match != null) {
            imageUrl = match.groupValues[2]
            body = body.replace(match.value, "").trim()
          }
          val (parsedDesc, parsedSections) = parseMarkdownToSections(body)
          description = parsedDesc ?: body.takeIf { parsedSections.isEmpty() }
          changelogList.addAll(parsedSections)
        }

        val publishedAt = targetRelease.optString("published_at", "")
        val formattedReleaseDate = formatGitHubDate(publishedAt)
        val assets = targetRelease.optJSONArray("assets") ?: JSONArray()

        val selectedAsset = selectBestApkAsset(assets)
        val apkSizeInMB =
          selectedAsset?.let {
            String.format("%.1f", it.optLong("size", 0L) / (1024.0 * 1024.0))
          } ?: ""
        val apkDownloadUrl =
          selectedAsset?.optString("browser_download_url")?.takeIf { it.isNotBlank() }

        latestCachedUpdateAsset =
          CachedUpdateAsset(
            version = displayTag,
            apkUrl = apkDownloadUrl,
            fileSize = apkSizeInMB,
          )

        withContext(Dispatchers.Main) {
          onSuccess(
            displayTag,
            true,
            changelogList,
            apkSizeInMB,
            formattedReleaseDate,
            description,
            imageUrl,
            apkDownloadUrl,
          )
        }
        return@withContext
      }

      withContext(Dispatchers.Main) {
        onSuccess(currentVersion, false, emptyList(), "", "", null, null, null)
      }
    } catch (e: Exception) {
      Log.e("UpdateCheck", "Error checking for updates: ${e.message}", e)
      withContext(Dispatchers.Main) { onError() }
    }
  }
}

const val KEY_LAST_SEEN_CHANGELOG_VERSION = "last_seen_changelog_version"

fun getLastSeenChangelogVersion(context: Context): String {
  val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  return sharedPrefs.getString(KEY_LAST_SEEN_CHANGELOG_VERSION, "") ?: ""
}

fun saveLastSeenChangelogVersion(context: Context, version: String) {
  val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  sharedPrefs.edit().putString(KEY_LAST_SEEN_CHANGELOG_VERSION, version).apply()
}

data class WhatsNewInfo(
  val changelog: List<ChangelogSection>,
  val description: String?,
)

private fun selectBestApkAsset(assets: JSONArray): JSONObject? {
  val allApks = mutableListOf<JSONObject>()
  for (j in 0 until assets.length()) {
    val asset = assets.getJSONObject(j)
    val assetName = asset.optString("name", "")
    if (assetName.endsWith(".apk", ignoreCase = true)) {
      allApks.add(asset)
    }
  }
  if (allApks.isEmpty()) return null

  val candidates =
    if (BuildConfig.DEBUG) {
      allApks.filter { it.optString("name", "").lowercase().contains("debug") }.ifEmpty { allApks }
    } else {
      allApks.filter { !it.optString("name", "").lowercase().contains("debug") }.ifEmpty { allApks }
    }

  val preferredVariant = if (BuildConfig.CAST_AVAILABLE) "gms" else "foss"
  val variantFiltered =
    candidates.filter { it.optString("name", "").lowercase().contains(preferredVariant) }
      .ifEmpty { candidates }

  val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull()?.lowercase().orEmpty()
  val abiTokens =
    when {
      primaryAbi.contains("arm64") -> listOf("arm64-v8a", "arm64")
      primaryAbi.contains("armeabi") -> listOf("armeabi-v7a", "armeabi")
      primaryAbi.contains("x86_64") -> listOf("x86_64")
      primaryAbi.contains("x86") -> listOf("x86")
      else -> emptyList()
    }

  return variantFiltered.firstOrNull { asset ->
    val name = asset.optString("name", "").lowercase()
    abiTokens.any { token ->
      if (token == "x86") name.contains("x86") && !name.contains("x86_64")
      else name.contains(token)
    }
  }
    ?: variantFiltered.firstOrNull { asset ->
      asset.optString("name", "").lowercase().contains("universal")
    }
    ?: variantFiltered.firstOrNull()
}

/**
 * URLConnection defaults both connect and read timeouts to 0 (infinite) — opening a stream without
 * setting them can hang this Dispatchers.IO call indefinitely on a stalled request.
 */
private fun openTimedStream(url: String): java.io.InputStream =
  (URL(url).openConnection() as HttpURLConnection)
    .apply {
      connectTimeout = 15_000
      readTimeout = 15_000
      setRequestProperty("User-Agent", "Echo-Music")
      if (url.contains("api.github.com")) {
        setRequestProperty("Accept", "application/vnd.github+json")
      }
    }
    .inputStream

/**
 * Looks up the release whose tag matches [currentVersion] (not necessarily the latest — the user
 * may be a version or two behind "latest" right after installing an update) and returns its
 * changelog, mirroring the parsing [checkForUpdate] does for the newest release.
 */
suspend fun fetchChangelogForVersion(currentVersion: String): WhatsNewInfo? =
  withContext(Dispatchers.IO) {
    try {
      val targetVersion = INSTALLED_RELEASE_TAG.ifBlank { currentVersion }
      val cleanCurrent = targetVersion.removePrefix("b").removePrefix("v").trim()
      val releasesJson =
        openTimedStream(GITHUB_RELEASES_API_URL)
          .bufferedReader()
          .use { it.readText() }
      val releases = JSONArray(releasesJson)

      var matchedRelease: JSONObject? = null
      for (i in 0 until releases.length()) {
        val release = releases.getJSONObject(i)
        val rawTag = release.optString("tag_name").trim()
        val tagClean = rawTag.removePrefix("b").removePrefix("v").trim()
        if (rawTag == targetVersion || tagClean == cleanCurrent) {
          matchedRelease = release
          break
        }
      }
      val release = matchedRelease ?: return@withContext null
      val tag = release.getString("tag_name")

      val changelogList = mutableListOf<ChangelogSection>()
      var description: String? = null
      try {
        val changelogJson =
          openTimedStream(
              "$GITHUB_RELEASES_URL/download/$tag/changelog.json",
            )
            .bufferedReader()
            .use { it.readText() }
        val changelogData = JSONObject(changelogJson)
        description = changelogData.optString("description").takeIf { it.isNotEmpty() }
        val changelogArray = changelogData.getJSONArray("changelog")
        for (j in 0 until changelogArray.length()) {
          val sectionObj = changelogArray.getJSONObject(j)
          val title = sectionObj.getString("title")
          val itemsArray = sectionObj.getJSONArray("items")
          val itemsList = mutableListOf<String>()
          for (k in 0 until itemsArray.length()) {
            itemsList.add(itemsArray.getString(k))
          }
          changelogList.add(ChangelogSection(title, itemsList))
        }
      } catch (e: Exception) {
        var body = release.optString("body", "")
        val imageRegex = Regex("!\\[(.*?)\\]\\((.*?)\\)")
        imageRegex.find(body)?.let { match -> body = body.replace(match.value, "").trim() }
        val (parsedDesc, parsedSections) = parseMarkdownToSections(body)
        description = parsedDesc ?: body.takeIf { parsedSections.isEmpty() }
        changelogList.addAll(parsedSections)
      }

      if (changelogList.isEmpty() && description.isNullOrBlank()) return@withContext null
      WhatsNewInfo(changelogList, description)
    } catch (e: Exception) {
      Log.e("WhatsNew", "Failed to fetch changelog for version $currentVersion: ${e.message}", e)
      null
    }
  }

@Composable
fun WhatsNewDialog(
  version: String,
  info: WhatsNewInfo,
  onDismiss: () -> Unit,
) {
  DefaultDialog(
    onDismiss = onDismiss,
    title = { Text(stringResource(R.string.whats_new_title, version)) },
    buttons = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } },
  ) {
    val (effectiveDescription, effectiveSections) =
      remember(info) {
        if (info.changelog.isNotEmpty()) {
          Pair(info.description?.takeIf { it.isNotBlank() }, info.changelog)
        } else if (!info.description.isNullOrBlank()) {
          parseMarkdownToSections(info.description)
        } else {
          Pair(null, emptyList())
        }
      }

    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
      effectiveDescription?.let { description ->
        item {
          Text(
            text = parseSimpleMarkdown(description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp),
          )
        }
      }
      effectiveSections.forEach { section ->
        if (section.title.isNotBlank()) {
          item {
            Text(
              text = section.title,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
            )
          }
        }
        itemsIndexed(section.items) { index, changelogItem ->
          val shape =
            when {
              section.items.size == 1 -> detachedItemShape()
              index == 0 -> leadingItemShape()
              index == section.items.size - 1 -> endItemShape()
              else -> middleItemShape()
            }
          ChangelogItem(text = changelogItem, shape = shape)
          if (index != section.items.size - 1) {
            Spacer(modifier = Modifier.height(2.dp))
          }
        }
      }
    }
  }
}

fun String.extractUrls(): List<Pair<IntRange, String>> {
  val urlPattern =
    Pattern.compile("(?:^|[\\s])((https?://|www\\.|pic\\.)[\\w-]+(\\.[\\w-]+)+([/?].*)?)")
  val matcher = urlPattern.matcher(this)
  val urlList = mutableListOf<Pair<IntRange, String>>()

  while (matcher.find()) {
    val url = matcher.group(1)?.trim() ?: continue
    val range = IntRange(matcher.start(1), matcher.end(1) - 1)

    val fullUrl = if (url.startsWith("http")) url else "https://$url"
    urlList.add(range to fullUrl)
  }

  return urlList
}
