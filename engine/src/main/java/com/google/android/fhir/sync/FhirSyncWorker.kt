/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.fhir.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.FhirEngineProvider
import com.google.android.fhir.OffsetDateTimeTypeAdapter
import com.google.gson.ExclusionStrategy
import com.google.gson.FieldAttributes
import com.google.gson.GsonBuilder
import java.time.OffsetDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/** A WorkManager Worker that handles periodic sync. */
abstract class FhirSyncWorker(appContext: Context, workerParams: WorkerParameters) :
  CoroutineWorker(appContext, workerParams) {
  abstract fun getFhirEngine(): FhirEngine
  abstract fun getDownloadWorkManager(): DownloadWorkManager
  private val mutex = Mutex()
  abstract fun getConflictResolver(): ConflictResolver

  /**
   * Configuration defining the max upload Bundle size (in terms to number of resources in a Bundle)
   * and optionally defining the order of Resources.
   */
  open fun getUploadConfiguration(): UploadConfiguration = UploadConfiguration()

  private val gson =
    GsonBuilder()
      .registerTypeAdapter(OffsetDateTime::class.java, OffsetDateTimeTypeAdapter().nullSafe())
      .setExclusionStrategies(StateExclusionStrategy())
      .create()

  /** The purpose of this api makes it easy to stub [FhirSyncWorker] for testing. */
  internal open fun getDataSource() = FhirEngineProvider.getDataSource(applicationContext)

  override suspend fun doWork(): Result {
    mutex.withLock {
      val dataSource =
        getDataSource()
          ?: return Result.failure(
            buildWorkData(
              IllegalStateException(
                "FhirEngineConfiguration.ServerConfiguration is not set. Call FhirEngineProvider.init to initialize with appropriate configuration."
              )
            )
          )
      val flow = MutableSharedFlow<SyncJobStatus>()

      val fhirSynchronizer =
        FhirSynchronizer(
          applicationContext,
          getFhirEngine(),
          dataSource,
          getDownloadWorkManager(),
          conflictResolver = getConflictResolver()
        )

      val job =
        CoroutineScope(Dispatchers.IO).launch {
          flow.collect {
            // now send Progress to work manager so caller app can listen
            kotlin.runCatching { setProgress(buildWorkData(it)) }.onFailure(Timber::i)

            if (it is SyncJobStatus.Finished || it is SyncJobStatus.Failed) {
              this@launch.cancel()
            }
          }
        }

      val result =
        fhirSynchronizer
          .apply { subscribe(flow) }
          .synchronize(
            SyncWorkType.valueOf(
              inputData.getString(SYNC_TYPE) ?: "fhir-engine-download-upload-worker"
            )
          )

      val output = buildWorkData(result)

      // await/join is needed to collect states completely
      kotlin.runCatching { job.join() }.onFailure(Timber::w)

      kotlin.runCatching { setProgress(output) }.onFailure(Timber::i)

      Timber.d("Received result from worker $result and sending output $output")

      /**
       * In case of failure, we can check if its worth retrying and do retry based on
       * [RetryConfiguration.maxRetries] set by user.
       */
      val retries = inputData.getInt(MAX_RETRIES_ALLOWED, 0)
      return when {
        result is SyncJobStatus.Finished -> {
          Result.success(output)
        }
        retries > runAttemptCount -> {
          Result.retry()
        }
        else -> {
          Result.failure(output)
        }
      }
    }
  }

  private fun buildWorkData(state: SyncJobStatus): Data {
    return workDataOf(
      // send serialized state and type so that consumer can convert it back
      "StateType" to state::class.java.name,
      "State" to gson.toJson(state)
    )
  }

  private fun buildWorkData(exception: Exception): Data {
    return workDataOf("error" to exception::class.java.name, "reason" to exception.message)
  }

  /**
   * Exclusion strategy for [Gson] that handles field exclusions for [SyncJobStatus] returned by
   * FhirSynchronizer. It should skip serializing the exceptions to avoid exceeding WorkManager
   * WorkData limit
   * @see <a
   * href="https://github.com/google/android-fhir/issues/707">https://github.com/google/android-fhir/issues/707</a>
   */
  internal class StateExclusionStrategy : ExclusionStrategy {
    override fun shouldSkipField(field: FieldAttributes) = field.name.equals("exceptions")

    override fun shouldSkipClass(clazz: Class<*>?) = false
  }

  companion object {
    const val SYNC_TYPE = "sync-type"
  }
}
