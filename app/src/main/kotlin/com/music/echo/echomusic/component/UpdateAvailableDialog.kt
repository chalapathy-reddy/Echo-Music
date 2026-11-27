package echo.music.iad1tya.echomusic.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import androidx.work.WorkManager
import echo.music.iad1tya.R
import echo.music.iad1tya.echomusic.updater.ChangelogSection
import echo.music.iad1tya.echomusic.updater.GITHUB_RELEASES_URL
import echo.music.iad1tya.echomusic.updater.enqueueApkUpdateDownload
import echo.music.iad1tya.echomusic.updater.installDownloadedApk
import echo.music.iad1tya.echomusic.updater.latestCachedUpdateAsset
import echo.music.iad1tya.ui.utils.parseMarkdownToSections
import echo.music.iad1tya.ui.utils.parseSimpleMarkdown
import echo.music.iad1tya.ui.utils.safeOpenUri
import java.io.File
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

/**
 * Displays a dialog informing the user that a newer version of the application is available.
 *
 * @param version The latest version tag or display string.
 * @param changelog Pre-parsed structured changelog sections, if available.
 * @param description Raw release description or fallback Markdown text.
 * @param onDismiss Invoked when the user dismisses the dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateAvailableDialog(
  version: String,
  changelog: List<ChangelogSection>,
  description: String?,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  var isDownloading by remember { mutableStateOf(false) }
  var downloadProgress by remember { mutableStateOf(0f) }
  var isDownloadComplete by remember { mutableStateOf(false) }
  var downloadedFile by remember { mutableStateOf<File?>(null) }
  var downloadError by remember { mutableStateOf<String?>(null) }

  val cachedAsset =
    remember(version, latestCachedUpdateAsset) {
      latestCachedUpdateAsset?.takeIf { it.version == version } ?: latestCachedUpdateAsset
    }

  DisposableEffect(version) {
    val liveData =
      WorkManager.getInstance(context).getWorkInfosForUniqueWorkLiveData("update_download")
    val observer =
      Observer<List<WorkInfo>> { workInfos ->
        val workInfo = workInfos.firstOrNull() ?: return@Observer
        if (!workInfo.tags.contains("version:$version")) return@Observer
        when (workInfo.state) {
          WorkInfo.State.RUNNING -> {
            isDownloading = true
            downloadError = null
            downloadProgress = workInfo.progress.getFloat("progress", 0f)
          }
          WorkInfo.State.SUCCEEDED -> {
            isDownloading = false
            isDownloadComplete = true
            downloadError = null
            val filePath = workInfo.outputData.getString("file_path")
            if (filePath != null) {
              downloadedFile = File(filePath)
            }
          }
          WorkInfo.State.FAILED -> {
            isDownloading = false
            downloadError = context.getString(R.string.download_failed)
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
    if (isDownloadComplete && downloadedFile != null && !downloadedFile!!.exists()) {
      isDownloadComplete = false
      downloadedFile = null
      downloadProgress = 0f
    }
  }
  val cardShape =
    AbsoluteSmoothCornerShape(
      cornerRadiusTL = 30.dp,
      cornerRadiusTR = 30.dp,
      cornerRadiusBL = 30.dp,
      cornerRadiusBR = 30.dp,
      smoothnessAsPercentTL = 60,
      smoothnessAsPercentTR = 60,
      smoothnessAsPercentBL = 60,
      smoothnessAsPercentBR = 60,
    )
  val blockShape = AbsoluteSmoothCornerShape(22.dp, 60)
  val actionShape = AbsoluteSmoothCornerShape(18.dp, 60)

  BasicAlertDialog(onDismissRequest = onDismiss) {
    Surface(
      modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
      shape = cardShape,
      color = MaterialTheme.colorScheme.surfaceContainerHigh,
      tonalElevation = 8.dp,
    ) {
      Column(
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        Surface(
          shape = blockShape,
          color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
          Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Surface(
                shape = AbsoluteSmoothCornerShape(12.dp, 60),
                color = MaterialTheme.colorScheme.primaryContainer,
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  Icon(
                    painter = painterResource(R.drawable.update),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(16.dp),
                  )
                  Text(
                    text = stringResource(R.string.update_available_title),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                  )
                }
              }
            }

            Text(
              text = "Version $version is available",
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
            )
          }
        }

        if (changelog.isNotEmpty() || !description.isNullOrEmpty()) {
          Surface(
            modifier = Modifier.weight(1f, fill = false),
            shape = blockShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
          ) {
            Column(
              modifier =
                Modifier.fillMaxWidth().padding(14.dp).verticalScroll(rememberScrollState()),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Text(
                text = stringResource(R.string.changelog),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
              )

              val (effectiveDescription, effectiveSections) =
                remember(changelog, description) {
                  if (changelog.isNotEmpty()) {
                    Pair(description?.takeIf { it.isNotBlank() }, changelog)
                  } else if (!description.isNullOrBlank()) {
                    parseMarkdownToSections(description)
                  } else {
                    Pair(null, emptyList())
                  }
                }

              if (!effectiveDescription.isNullOrBlank()) {
                Text(
                  text =
                    parseSimpleMarkdown(effectiveDescription, MaterialTheme.colorScheme.primary),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.padding(bottom = 4.dp),
                )
              }

              if (effectiveSections.isNotEmpty()) {
                effectiveSections.forEach { section ->
                  if (section.title.isNotBlank()) {
                    Text(
                      text = section.title,
                      style = MaterialTheme.typography.titleSmall,
                      fontWeight = FontWeight.Bold,
                      color = MaterialTheme.colorScheme.primary,
                      modifier = Modifier.padding(top = 6.dp),
                    )
                  }
                  section.items.forEach { item ->
                    Row(
                      modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                      horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                      Text(
                        text = "•",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                      )
                      Text(
                        text = parseSimpleMarkdown(item.trim(), MaterialTheme.colorScheme.primary),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                      )
                    }
                  }
                }
              }
            }
          }
        }

        if (isDownloading) {
          if (downloadProgress > 0f) {
            LinearProgressIndicator(
              progress = downloadProgress,
              modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
              color = MaterialTheme.colorScheme.primary,
              trackColor = MaterialTheme.colorScheme.surfaceContainer,
            )
          } else {
            LinearProgressIndicator(
              modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
              color = MaterialTheme.colorScheme.primary,
              trackColor = MaterialTheme.colorScheme.surfaceContainer,
            )
          }
        }

        downloadError?.let { errorMessage ->
          Text(
            text = errorMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.End,
        ) {
          TextButton(
            onClick = onDismiss,
            shape = actionShape,
          ) {
            Text(text = if (isDownloading) "Hide" else "Next time")
          }
          Spacer(modifier = Modifier.width(8.dp))
          Button(
            onClick = {
              if (isDownloadComplete) {
                val file = downloadedFile
                if (file == null || !file.exists()) {
                  isDownloadComplete = false
                  downloadedFile = null
                  downloadProgress = 0f
                  return@Button
                }
                installDownloadedApk(
                  context = context,
                  file = file,
                  fallbackUrl = cachedAsset?.apkUrl,
                  onInstallError = { downloadError = context.getString(R.string.download_failed) },
                )
              } else {
                val urlToDownload = cachedAsset?.apkUrl
                if (urlToDownload.isNullOrBlank()) {
                  onDismiss()
                  context.safeOpenUri(GITHUB_RELEASES_URL)
                } else {
                  downloadError = null
                  enqueueApkUpdateDownload(
                    context = context,
                    apkUrl = urlToDownload,
                    version = version,
                    fileSize = cachedAsset?.fileSize.orEmpty(),
                  )
                  isDownloading = true
                }
              }
            },
            enabled = !isDownloading || isDownloadComplete,
            shape = actionShape,
          ) {
            Text(
              text =
                if (isDownloading) "${(downloadProgress * 100).toInt()}%"
                else if (isDownloadComplete) stringResource(R.string.install)
                else "Update",
            )
          }
        }
      }
    }
  }
}
