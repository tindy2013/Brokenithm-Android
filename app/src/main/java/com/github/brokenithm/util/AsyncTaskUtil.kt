package com.github.brokenithm.util

import kotlinx.coroutines.*

@Suppress("unused")
object AsyncTaskUtil {
    fun <R> CoroutineScope.executeAsyncTask(
        onPreExecute: () -> Unit = {},
        doInBackground: () -> R,
        onPostExecute: (R) -> Unit = {},
        onCancelled: () -> Unit = {}
    ) = launch {
        try {
            onPreExecute() // runs in Main Thread
            val result = withContext(Dispatchers.IO) {
                doInBackground() // runs in background thread without blocking the Main Thread
            }
            onPostExecute(result) // runs in Main Thread
        } catch (e: CancellationException) {
            onCancelled()
        }
    }

    fun <P, R> CoroutineScope.executeAsyncTask(
        onPreExecute: () -> Unit = {},
        doInBackground: suspend (suspend (P) -> Unit) -> R,
        onPostExecute: (R) -> Unit = {},
        onProgressUpdate: (P) -> Unit,
        onCancelled: () -> Unit = {}
    ) = launch {
        try {
            onPreExecute()
            val result = withContext(Dispatchers.IO) {
                doInBackground {
                    withContext(Dispatchers.Main) { onProgressUpdate(it) }
                }
            }
            onPostExecute(result)
        } catch (e: CancellationException) {
            onCancelled()
        }
    }

    fun CoroutineScope.executeDelayedTask(
        task: () -> Unit,
        delayMillis: Long,
        onCancelled: () -> Unit = {}
    ) = launch {
        try {
            delay(delayMillis)
            withContext(Dispatchers.Main) { task() }
        } catch (e: CancellationException) {
            onCancelled()
        }
    }

    fun <A, P, R> CoroutineScope.executeAsyncTask(task: AsyncTask<A, P, R>, vararg arguments: A) = launch {
        try {
            task.onPreExecute()
            val result = withContext(Dispatchers.IO) {
                task.doInBackground(*arguments)
            }
            task.onPostExecute(result)
        } catch (e: CancellationException) {
            task.onCancelled()
        }
    }

    abstract class AsyncTask<A, P, R> private constructor() {
        open fun onPreExecute() {}
        abstract suspend fun doInBackground(vararg argument: A): R
        open fun onPostExecute(result: R) {}
        open fun onCancelled() {}
        open fun onProgressUpdate(progress: P) {}
        protected fun publishProgress(progress: P) { onProgressUpdate(progress) }

        private var mJob: Job? = null
        val isCompleted: Boolean
            get() =  mJob?.isCompleted ?: false
        val isActive: Boolean
            get() = mJob?.isActive ?: false
        val isCancelled: Boolean
            get() = mJob?.isCancelled ?: false

        fun execute(scope: CoroutineScope, vararg argument: A) {
            if (mJob != null && (!mJob!!.isCompleted || mJob!!.isActive)) {
                mJob!!.cancel()
                mJob = null
            }
            mJob = scope.executeAsyncTask(this, *argument)
        }

        fun cancel(exception: CancellationException? = null) {
            if (mJob != null && !mJob!!.isCancelled) {
                mJob!!.cancel(exception)
            }
        }

        fun cancel(message: String, cause: Throwable? = null) {
            if (mJob != null && !mJob!!.isCancelled) {
                mJob!!.cancel(message, cause)
            }
        }

        companion object {

            fun <A, R> make(
                onPreExecute: () -> Unit = {},
                doInBackground: (Array<out A>) -> R,
                onPostExecute: (R) -> Unit = {},
                onCancelled: () -> Unit = {}
            ): AsyncTask<A, Unit, R> {
                return object : AsyncTask<A, Unit, R>() {
                    override fun onPreExecute() { onPreExecute() }
                    override suspend fun doInBackground(vararg argument: A): R { return doInBackground(argument) }
                    override fun onPostExecute(result: R) { onPostExecute(result) }
                    override fun onCancelled() { onCancelled() }
                }
            }

            fun <A, P, R> make(
                onPreExecute: () -> Unit = {},
                doInBackground: (Array<out A>) -> R,
                onPostExecute: (R) -> Unit = {},
                onProgressUpdate: (P) -> Unit = {},
                onCancelled: () -> Unit = {}
            ): AsyncTask<A, P, R> {
                return object : AsyncTask<A, P, R>() {
                    override fun onPreExecute() { onPreExecute() }
                    override suspend fun doInBackground(vararg argument: A): R { return doInBackground(argument) }
                    override fun onPostExecute(result: R) { onPostExecute(result) }
                    override fun onProgressUpdate(progress: P) { onProgressUpdate(progress) }
                    override fun onCancelled() { onCancelled() }
                }
            }
        }
    }
}